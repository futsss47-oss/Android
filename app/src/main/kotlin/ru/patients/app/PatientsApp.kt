package ru.patients.app

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.patients.app.data.Repository
import ru.patients.app.data.Settings
import ru.patients.app.reminders.Alarms
import ru.patients.app.sync.SyncManager

class PatientsApp : Application() {
    lateinit var settings: Settings
        private set
    lateinit var repo: Repository
        private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        repo = Repository(this)
        Alarms.ensureChannel(this)
        SyncManager.init(this)
        Alarms.reschedule(this, repo.state.value)
    }

    companion object {
        private fun app(ctx: Context) = ctx.applicationContext as PatientsApp
        fun repo(ctx: Context) = app(ctx).repo
        fun settings(ctx: Context) = app(ctx).settings

        fun syncInBackground(ctx: Context) {
            val a = app(ctx)
            if (!a.settings.syncConfigured) return
            a.scope.launch { SyncManager.sync(a) }
        }
    }
}
