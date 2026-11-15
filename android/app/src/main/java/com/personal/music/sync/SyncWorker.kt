package com.personal.music.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.personal.music.MusicApp
import java.util.concurrent.TimeUnit

/** WorkManager entry point: runs one [SyncRunner] pass, shown as a foreground notification. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val runner = (applicationContext as MusicApp).container.syncRunner
        showProgress(0, 0)
        runner.run(
            prioritySongId = inputData.getString(KEY_PRIORITY_SONG),
            onProgress = { done, total -> runCatchingQuietly { showProgressBlocking(done, total) } },
        )
        // The outcome is recorded in settings and shown in the app; the periodic work
        // picks up whatever is left, so there is nothing for WorkManager to retry.
        return Result.success()
    }

    private suspend fun showProgress(done: Int, total: Int) {
        // Starting a foreground service can be refused when the app is in the background;
        // the sync still runs as ordinary background work in that case.
        try {
            setForeground(foregroundInfo(done, total))
        } catch (e: IllegalStateException) {
            // Not allowed right now.
        }
    }

    private fun showProgressBlocking(done: Int, total: Int) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification(done, total))
    }

    private fun foregroundInfo(done: Int, total: Int) = ForegroundInfo(
        NOTIFICATION_ID,
        notification(done, total),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    private fun notification(done: Int, total: Int): android.app.Notification {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Music sync", NotificationManager.IMPORTANCE_LOW)
        )
        val text = if (total > 0) "Downloading song ${minOf(done + 1, total)} of $total" else "Checking for new songs"
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Syncing music")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total, done, total == 0)
            .build()
    }

    private inline fun runCatchingQuietly(block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            // A notification update is never worth failing a sync for.
        }
    }

    companion object {
        const val KEY_PRIORITY_SONG = "prioritySongId"
        private const val CHANNEL_ID = "sync"
        private const val NOTIFICATION_ID = 1001
    }
}

object SyncScheduler {
    private const val PERIODIC = "sync-periodic"
    private const val NOW = "sync-now"

    // "Connected" only: the Wi-Fi-only rule is enforced inside SyncRunner, because the
    // user can switch it off in Settings.
    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun syncNow(context: Context, prioritySongId: String? = null) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(SyncWorker.KEY_PRIORITY_SONG to prioritySongId))
            .build()
        // Never REPLACE: that would cancel a pass half-way through a download.
        // A plain "sync now" is satisfied by a pass that is already queued or running.
        // A priority song is appended, so it is fetched right after the current pass.
        val policy =
            if (prioritySongId == null) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.APPEND_OR_REPLACE
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, policy, request)
    }
}
