package com.dermalens.app.data.sync

import android.content.Context
import android.util.Log
import com.dermalens.app.data.db.DermaDatabase
import com.dermalens.app.data.model.ScanRecord
import com.dermalens.app.ui.screens.DermaPrefs
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Opt-in "Back Up Scan History": copies the signed-in account's scan *records* (condition,
 * confidence, severity, notes, date, timeline grouping) to Firestore so they reappear when the
 * same account logs in on another phone. Photos are never uploaded -- they stay on the phone
 * that took them (cloud file storage would need Firebase's paid Blaze plan, and keeping skin
 * photos off servers is the stronger privacy default anyway).
 *
 * Room stays the app's working copy; Firestore is only the backup. Writes are fire-and-forget:
 * Firestore's built-in offline persistence queues them while offline and sends them when the
 * connection returns, so saving a scan never waits on the network.
 *
 * Layout:  users/{uid}                 { syncEnabled, updatedAt }
 *          users/{uid}/scans/{scanDate} { condition, confidence, severity, notes, scanDate,
 *                                         groupKey, deleted, updatedAt }
 *
 * Local Room ids differ per phone, so a scan is identified across devices by its scanDate
 * (millisecond timestamp, set once at creation). Progress timelines are carried the same way:
 * groupKey is the scanDate of the timeline's first scan. Deleting a scan writes a tombstone
 * (deleted=true, health fields dropped) instead of removing the document, so another phone that
 * still has its local copy deletes it on next sync rather than re-uploading it.
 */
object ScanHistorySync {
    private const val TAG = "DermaLensSync"

    private fun firestore() = FirebaseFirestore.getInstance()
    private fun userRef(uid: String) = firestore().collection("users").document(uid)
    private fun scansRef(uid: String) = userRef(uid).collection("scans")
    private fun currentUid(): String? = FirebaseAuth.getInstance().currentUser?.uid
    private fun prefs(context: Context) =
        context.getSharedPreferences(DermaPrefs.PREFS_NAME, Context.MODE_PRIVATE)

    private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
    }

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(DermaPrefs.KEY_SYNC_HISTORY, false)

    /** Called on logout, so the next account to sign in on this phone doesn't inherit the choice. */
    fun clearLocalFlag(context: Context) {
        prefs(context).edit().remove(DermaPrefs.KEY_SYNC_HISTORY).apply()
    }

    private suspend fun localUserId(context: Context): Int? {
        val email = prefs(context).getString(DermaPrefs.KEY_USER_EMAIL, "") ?: ""
        if (email.isBlank()) return null
        return DermaDatabase.getDatabase(context).userDao().getUserByEmail(email)?.userId
    }

    /** scanDate of the first scan in this scan's Progress timeline (its own, if it starts one). */
    private suspend fun groupKeyOf(context: Context, scan: ScanRecord): Long {
        val rootId = scan.trackGroupId ?: return scan.scanDate
        if (rootId == scan.id) return scan.scanDate
        return DermaDatabase.getDatabase(context).scanRecordDao().getScanById(rootId)?.scanDate ?: scan.scanDate
    }

    private fun recordFields(scan: ScanRecord, groupKey: Long): Map<String, Any> = mapOf(
        "condition" to scan.condition,
        "confidence" to scan.confidence.toDouble(),
        "severity" to scan.severity,
        "notes" to scan.notes,
        "scanDate" to scan.scanDate,
        "groupKey" to groupKey,
        "deleted" to false,
        "updatedAt" to FieldValue.serverTimestamp(),
    )

    /** Uploads (or overwrites) one scan record. No-op unless backup is on and someone is signed in. */
    suspend fun pushScan(context: Context, scan: ScanRecord) {
        if (!isEnabled(context)) return
        val uid = currentUid() ?: return
        val fields = recordFields(scan, groupKeyOf(context, scan))
        scansRef(uid).document(scan.scanDate.toString()).set(fields)
            .addOnFailureListener { Log.e(TAG, "pushScan failed", it) }
    }

    /** Pushes a note edit. The whole record is rewritten, which also heals a scan that never synced. */
    suspend fun pushNotes(context: Context, scanId: Int) {
        val scan = DermaDatabase.getDatabase(context).scanRecordDao().getScanById(scanId) ?: return
        pushScan(context, scan)
    }

    /** Replaces the record with a tombstone that carries no health data, only "this was deleted". */
    fun pushDelete(context: Context, scanDate: Long) {
        if (!isEnabled(context)) return
        val uid = currentUid() ?: return
        scansRef(uid).document(scanDate.toString())
            .set(mapOf("scanDate" to scanDate, "deleted" to true, "updatedAt" to FieldValue.serverTimestamp()))
            .addOnFailureListener { Log.e(TAG, "pushDelete failed", it) }
    }

    /**
     * Turns backup on for this account: records the choice in the cloud and uploads every local
     * scan. Waits briefly for the server so a rejected write (e.g. missing security rules) surfaces
     * as an error instead of a switch that says "on" while nothing is saved; offline, the write
     * stays queued and backup counts as on.
     */
    suspend fun enable(context: Context) {
        val uid = currentUid() ?: throw IllegalStateException("Not signed in")
        val write = userRef(uid).set(mapOf("syncEnabled" to true, "updatedAt" to FieldValue.serverTimestamp()), SetOptions.merge())
        try {
            kotlinx.coroutines.withTimeout(10_000) { write.awaitTask() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.w(TAG, "Offline -- backup enable queued")
        }
        prefs(context).edit().putBoolean(DermaPrefs.KEY_SYNC_HISTORY, true).apply()
        val userId = localUserId(context) ?: return
        DermaDatabase.getDatabase(context).scanRecordDao().getScansByUserOnce(userId).forEach { pushScan(context, it) }
    }

    /**
     * Turns backup off and deletes the cloud copy. Scans already on this phone are kept. The
     * deletes aren't awaited: offline, Firestore queues them and they run once the phone
     * reconnects, instead of the switch hanging until then.
     */
    suspend fun disable(context: Context) {
        val uid = currentUid() ?: throw IllegalStateException("Not signed in")
        deleteCloudScans(uid, awaitCommit = false)
        userRef(uid).set(mapOf("syncEnabled" to false, "updatedAt" to FieldValue.serverTimestamp()), SetOptions.merge())
        prefs(context).edit().putBoolean(DermaPrefs.KEY_SYNC_HISTORY, false).apply()
    }

    private suspend fun deleteCloudScans(uid: String, awaitCommit: Boolean) {
        val docs = scansRef(uid).get().awaitTask().documents
        docs.chunked(400).forEach { chunk ->
            val batch = firestore().batch()
            chunk.forEach { batch.delete(it.reference) }
            val commit = batch.commit()
            if (awaitCommit) commit.awaitTask()
        }
    }

    /**
     * For Delete Account: removes every cloud record for this account. Awaited (with a time
     * limit) and allowed to throw, so the caller stops before deleting the Firebase account --
     * once the account is gone, the app could never remove these documents again.
     * PERMISSION_DENIED is treated as "nothing stored" (rules not deployed means nothing could
     * ever have been written), so it can't block account deletion.
     */
    suspend fun deleteAllCloudData(uid: String) {
        try {
            kotlinx.coroutines.withTimeout(20_000) {
                deleteCloudScans(uid, awaitCommit = true)
                userRef(uid).delete().awaitTask()
            }
        } catch (e: com.google.firebase.firestore.FirebaseFirestoreException) {
            if (e.code != com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) throw e
        }
    }

    /**
     * Two-way merge for the signed-in account, run when Home opens:
     *  - learns whether backup is on for this account (so a new phone picks it up at login)
     *  - applies tombstones (deletes local copies of scans deleted on another phone)
     *  - restores cloud records missing locally (without photos), rebuilding timelines
     *  - brings note edits from the cloud into existing local scans
     *  - uploads local scans the cloud doesn't have yet
     *
     * Returns how many scans were restored. Never throws: an offline phone just skips this round.
     */
    suspend fun reconcile(context: Context): Int = withContext(Dispatchers.IO) {
        try {
            val uid = currentUid() ?: return@withContext 0
            val userId = localUserId(context) ?: return@withContext 0
            val enabled = userRef(uid).get().awaitTask().getBoolean("syncEnabled") == true
            prefs(context).edit().putBoolean(DermaPrefs.KEY_SYNC_HISTORY, enabled).apply()
            if (!enabled) return@withContext 0

            val dao = DermaDatabase.getDatabase(context).scanRecordDao()
            val cloud = scansRef(uid).get().awaitTask().documents
            val local = dao.getScansByUserOnce(userId).associateBy { it.scanDate }.toMutableMap()

            // 1. deletions made on another phone
            cloud.filter { it.getBoolean("deleted") == true }.forEach { doc ->
                val date = doc.getLong("scanDate") ?: return@forEach
                local.remove(date)?.let { scan ->
                    if (scan.imagePath.isNotEmpty()) java.io.File(scan.imagePath).delete()
                    dao.deleteScan(scan.id)
                }
            }

            // timeline roots already on this phone: groupKey (root's scanDate) -> local root id
            val rootIdByGroupKey = mutableMapOf<Long, Int>()
            local.values.forEach { scan -> if (scan.trackGroupId == scan.id) rootIdByGroupKey[scan.scanDate] = scan.id }

            // 2. restore + note updates, oldest first so each timeline's root exists before its members
            var restored = 0
            val live = cloud.filter { it.getBoolean("deleted") != true }.sortedBy { it.getLong("scanDate") ?: 0L }
            for (doc in live) {
                val date = doc.getLong("scanDate") ?: continue
                val notes = doc.getString("notes") ?: ""
                val existing = local[date]
                if (existing != null) {
                    if (existing.notes != notes) dao.updateNotes(existing.id, notes)
                    continue
                }
                val groupKey = doc.getLong("groupKey") ?: date
                val rootId = if (groupKey == date) null else rootIdByGroupKey[groupKey]
                val newId = dao.insertScan(
                    ScanRecord(
                        userId = userId,
                        condition = doc.getString("condition") ?: continue,
                        confidence = (doc.getDouble("confidence") ?: 0.0).toFloat(),
                        severity = doc.getString("severity") ?: "",
                        notes = notes,
                        scanDate = date,
                        imagePath = "", // photos are not backed up
                        trackGroupId = rootId,
                    )
                ).toInt()
                if (rootId == null) {
                    // Starts its timeline -- or its original first scan was deleted, in which case
                    // keying by groupKey (not date) keeps the rest of that timeline together.
                    dao.setTrackGroupId(newId, newId)
                    rootIdByGroupKey[groupKey] = newId
                }
                restored++
            }

            // 3. local scans the cloud has never seen (e.g. saved on this phone before backup was on)
            val cloudDates = cloud.mapNotNull { it.getLong("scanDate") }.toSet()
            local.values.filter { it.scanDate !in cloudDates }.forEach { pushScan(context, it) }

            if (restored > 0) Log.d(TAG, "Restored $restored scan(s) from backup")
            restored
        } catch (e: Exception) {
            Log.w(TAG, "Sync skipped this round", e)
            0
        }
    }
}
