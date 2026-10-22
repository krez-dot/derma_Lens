package com.dermalens.app.worker

import android.content.Context
import android.util.Base64
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dermalens.app.BuildConfig
import com.dermalens.app.data.db.DermaDatabase
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Uploads scans the user consented to contribute (see "Contribute to Research" in Profile) to
 * the user's own Google Drive via a Google Apps Script Web App bridge (see
 * apps-script/ContributionUpload.gs), then marks them uploaded so they aren't sent again. Runs
 * only on an unmetered connection (see [ContributionUploadScheduler]) so it never eats someone's
 * mobile data.
 *
 * Deliberately anonymous end to end: the remote filename is a random UUID plus just the detected
 * condition, never the user's account ID, email, or anything else that could tie it back to a
 * person -- matching exactly what the in-app consent dialog promises before anyone opts in. The
 * Apps Script endpoint runs under its owner's own Drive permissions, so nothing but a low-value
 * shared secret needs to live in the app -- no real credential is embedded in the APK.
 */
class ContributionUploadWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        if (BuildConfig.APPS_SCRIPT_URL.isBlank()) return Result.success()

        return try {
            val db = DermaDatabase.getDatabase(context)
            val pending = db.scanRecordDao().getPendingContributions()
            if (pending.isEmpty()) return Result.success()

            for (scan in pending) {
                val file = File(scan.imagePath)
                if (!file.exists()) continue // e.g. contribution was toggled off after saving

                val safeCondition = scan.condition.replace(Regex("[^A-Za-z0-9]"), "_")
                val remoteFilename = "${safeCondition}_${UUID.randomUUID()}.jpg"

                if (!uploadImage(file, remoteFilename, scan.condition)) continue
                db.scanRecordDao().markContributionUploaded(scan.scanId)
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun uploadImage(file: File, remoteFilename: String, condition: String): Boolean {
        val imageBase64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        val body = JSONObject().apply {
            put("secret", BuildConfig.CONTRIBUTION_UPLOAD_SECRET)
            put("filename", remoteFilename)
            put("condition", condition) // routes into a per-condition Drive subfolder -- see ContributionUpload.gs
            put("imageBase64", imageBase64)
        }

        val url = URL(BuildConfig.APPS_SCRIPT_URL)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            // Apps Script Web Apps always answer with a 302 to script.googleusercontent.com
            // before the real response -- letting HttpURLConnection auto-follow that resends
            // this POST's body to the redirect target, which Google's serving layer doesn't
            // accept (confirmed via logcat: an HTML "can't open this file" page, HTTP 404).
            // Follow it manually as a clean GET instead, same as a browser or curl -L does.
            instanceFollowRedirects = false
            connectTimeout = 20000
            readTimeout = 30000
            setRequestProperty("Content-Type", "application/json")
            outputStream.use { it.write(body.toString().toByteArray()) }
        }

        val finalConn = if (conn.responseCode in 300..399) {
            val redirectUrl = conn.getHeaderField("Location")
                ?: return false.also { android.util.Log.e("DermaLens", "Contribution upload redirect had no Location header") }
            (URL(redirectUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20000
                readTimeout = 30000
            }
        } else {
            conn
        }

        if (finalConn.responseCode !in 200..299) {
            val errorBody = finalConn.errorStream?.bufferedReader()?.readText() ?: "(no error body)"
            android.util.Log.e("DermaLens", "Contribution upload failed: HTTP ${finalConn.responseCode} -- $errorBody")
            return false
        }

        val response = JSONObject(finalConn.inputStream.bufferedReader().readText())
        if (response.optString("status") != "ok") {
            android.util.Log.e("DermaLens", "Contribution upload rejected: ${response.optString("message")}")
            return false
        }
        return true
    }

    companion object {
        const val WORK_NAME = "contribution_upload_work"
    }
}
