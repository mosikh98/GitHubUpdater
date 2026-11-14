package app.githubupdater

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

private const val CHANNEL_ID = "updates"
private const val WORK_NAME = "update_check"

object Scheduler {
    fun apply(context: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
        }
    }
}

class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val store = RepoStore(ctx)
        store.load().forEach { UpdateChecker.checkRepo(store, it.key) }

        val pm = ctx.packageManager
        val withUpdate = store.load().filter { repo ->
            val installed = repo.packageName?.let { StatusLogic.installedVersion(pm, it) }
            StatusLogic.compute(repo, installed) == Status.UpdateAvailable
        }
        val fresh = withUpdate.filter { "${it.key}@${it.latest?.tag}" !in store.notified() }
        if (fresh.isEmpty()) return Result.success()

        val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (allowed && NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
            Scheduler.createChannel(ctx)
            val title = if (fresh.size == 1) ctx.getString(R.string.notif_one, fresh[0].fullName)
            else ctx.getString(R.string.notif_many, fresh.size)
            val text = fresh.joinToString(", ") { "${it.fullName} ${it.latest?.tag}" }
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_update)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(ctx).notify(1001, n)
        }
        store.addNotified(fresh.map { "${it.key}@${it.latest?.tag}" })
        return Result.success()
    }
}
