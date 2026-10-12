package com.dermalens.app.worker

import android.content.Context
import androidx.work.*
import com.dermalens.app.ui.screens.DermaPrefs
import java.util.Calendar
import java.util.concurrent.TimeUnit

object NotificationScheduler {

    /** Reminder time used until the user picks one of their own. */
    const val DEFAULT_HOUR = 9
    const val DEFAULT_MINUTE = 0

    private fun prefs(context: Context) =
        context.getSharedPreferences(DermaPrefs.PREFS_NAME, Context.MODE_PRIVATE)

    fun reminderHour(context: Context): Int =
        prefs(context).getInt(DermaPrefs.KEY_REMINDER_HOUR, DEFAULT_HOUR)

    fun reminderMinute(context: Context): Int =
        prefs(context).getInt(DermaPrefs.KEY_REMINDER_MINUTE, DEFAULT_MINUTE)

    /** Persists the chosen time and re-arms the job so it takes effect immediately. */
    fun setReminderTime(context: Context, hour: Int, minute: Int) {
        prefs(context).edit()
            .putInt(DermaPrefs.KEY_REMINDER_HOUR, hour)
            .putInt(DermaPrefs.KEY_REMINDER_MINUTE, minute)
            .apply()
        scheduleDailyReminder(context, replaceExisting = true)
    }

    /** Milliseconds from [now] until the next [hour]:[minute]; rolls to tomorrow if that time has
     *  already passed today, so the result is always in (0, 24h]. */
    fun delayUntilNext(hour: Int, minute: Int, now: Calendar = Calendar.getInstance()): Long {
        val target = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (!target.after(now)) target.add(Calendar.DAY_OF_YEAR, 1)
        return target.timeInMillis - now.timeInMillis
    }

    /**
     * Arms the daily reminder for the user's chosen time.
     *
     * [replaceExisting] must be true whenever the time itself changed, and that path has to
     * *cancel first* rather than lean on a replace policy.
     *
     * setInitialDelay on a PeriodicWorkRequest only governs the FIRST run. Once a job has run at
     * least once its WorkSpec carries a period_count, and WorkManager then schedules from
     * lastEnqueueTime + interval and ignores the initial delay entirely. UPDATE makes that worse
     * by explicitly copying lastEnqueueTime off the old spec ("preserves enqueue time"). Either
     * way a newly picked time would silently never apply -- observed on device as a job still
     * sitting at +24h when the chosen time was 21h away. Cancelling drops the old WorkSpec and
     * its period_count, so the re-enqueued job honours the delay and lands on the chosen hour.
     *
     * It stays false for the re-arm on every app launch in MainActivity, where KEEP is what we
     * want -- that path must not restart the countdown, or opening the app daily would mean the
     * reminder never fires.
     */
    fun scheduleDailyReminder(context: Context, replaceExisting: Boolean = false) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()

        val reminderRequest = PeriodicWorkRequestBuilder<ScanReminderWorker>(
            24, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setInitialDelay(
                delayUntilNext(reminderHour(context), reminderMinute(context)),
                TimeUnit.MILLISECONDS
            )
            .build()

        val workManager = WorkManager.getInstance(context)
        if (replaceExisting) {
            // Operations on one unique name are serialised by WorkManager's task executor, so the
            // enqueue below sees a cleanly cancelled slot rather than racing this call.
            workManager.cancelUniqueWork(ScanReminderWorker.WORK_NAME)
        }
        workManager.enqueueUniquePeriodicWork(
            ScanReminderWorker.WORK_NAME,
            if (replaceExisting) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
            else ExistingPeriodicWorkPolicy.KEEP,
            reminderRequest
        )
    }

    fun cancelReminder(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(ScanReminderWorker.WORK_NAME)
    }

}

/** Schedules/cancels [ContributionUploadWorker], gated on the same "Contribute to Research"
 *  consent toggle as the local save step in ScanResultScreen.kt. */
object ContributionUploadScheduler {

    fun scheduleUpload(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED) // Wi-Fi (or otherwise unmetered) only
            .build()

        val uploadRequest = PeriodicWorkRequestBuilder<ContributionUploadWorker>(
            12, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            ContributionUploadWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            uploadRequest
        )
    }

    fun cancelUpload(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(ContributionUploadWorker.WORK_NAME)
    }

    /** Fires a one-time upload attempt right away, on top of the 12h periodic job above. Still
     *  Wi-Fi-only -- this doesn't relax the "never eats mobile data" promise, it just means a
     *  scan saved while already on Wi-Fi doesn't sit around for up to 12h before going out. If
     *  Wi-Fi isn't available yet, WorkManager holds this until it is, same as the periodic job
     *  would. Safe to call every time a scan is saved with consent -- if nothing's pending by the
     *  time this runs (e.g. the periodic job already grabbed it), the worker just no-ops. */
    fun triggerImmediateUpload(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()

        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<ContributionUploadWorker>()
                .setConstraints(constraints)
                .build()
        )
    }
}