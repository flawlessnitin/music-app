package com.personal.music

import android.app.Application
import androidx.work.Configuration
import com.personal.music.sync.SyncScheduler

class MusicApp : Application(), Configuration.Provider {
    val container: AppContainer by lazy { AppContainer(this) }

    // WorkManager is created on first use with this configuration.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        SyncScheduler.schedulePeriodic(this)
    }
}
