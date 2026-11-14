package app.githubupdater

import android.app.Application

class UpdaterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Scheduler.createChannel(this)
        Scheduler.apply(this, RepoStore(this).autoCheck)
    }
}
