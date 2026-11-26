package com.dermalens.app.data.db

import androidx.room.*
import com.dermalens.app.data.model.BoundingBox
import com.dermalens.app.data.model.ScanRecord
import com.dermalens.app.data.model.ScanTrigger
import com.dermalens.app.data.model.TrackGroup
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanRecordDao {

    // ABORT, not REPLACE: REPLACE deletes the old row before inserting, and with the cascading
    // foreign keys that silently took the scan's bounding boxes with it. Re-saves use updateScan.
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertScan(scan: ScanRecord): Long

    @Update
    suspend fun updateScan(scan: ScanRecord)

    // A scan's owner lives on its track group, so every "this user's scans" query goes through it.
    @Query("SELECT s.* FROM scan_records s INNER JOIN track_groups g ON s.groupId = g.groupId WHERE g.userId = :userId ORDER BY s.scanDate DESC")
    fun getScansByUser(userId: Int): Flow<List<ScanRecord>>

    @Query("SELECT s.* FROM scan_records s INNER JOIN track_groups g ON s.groupId = g.groupId WHERE g.userId = :userId ORDER BY s.scanDate DESC")
    suspend fun getScansByUserOnce(userId: Int): List<ScanRecord>

    @Query("SELECT * FROM scan_records WHERE scanId = :scanId LIMIT 1")
    suspend fun getScanById(scanId: Int): ScanRecord?

    @Query("SELECT DISTINCT s.condition FROM scan_records s INNER JOIN track_groups g ON s.groupId = g.groupId WHERE g.userId = :userId")
    suspend fun getConditionsByUser(userId: Int): List<String>

    // Scans the user consented to contribute, saved locally, but not yet uploaded to Google
    // Drive -- what ContributionUploadWorker works through on each run.
    @Query("SELECT * FROM scan_records WHERE contributedForTraining = 1 AND uploadedForTraining = 0 AND imagePath != ''")
    suspend fun getPendingContributions(): List<ScanRecord>

    @Query("UPDATE scan_records SET uploadedForTraining = 1 WHERE scanId = :scanId")
    suspend fun markContributionUploaded(scanId: Int)

    @Query("UPDATE scan_records SET notes = :notes WHERE scanId = :scanId")
    suspend fun updateNotes(scanId: Int, notes: String)

    @Query("DELETE FROM scan_records WHERE scanId = :scanId")
    suspend fun deleteScanRow(scanId: Int)

    @Query("DELETE FROM track_groups WHERE groupId NOT IN (SELECT groupId FROM scan_records)")
    suspend fun deleteEmptyGroups()

    /** Deletes a scan (its boxes go with it) and, if it was the last one, its track group. */
    @Transaction
    suspend fun deleteScan(scanId: Int) {
        deleteScanRow(scanId)
        deleteEmptyGroups()
    }

    // ── Track groups ──────────────────────────────────────────────────────

    @Insert
    suspend fun insertGroup(group: TrackGroup): Long

    // "Scan Again" only continues a group that still exists and belongs to the signed-in user --
    // its last scan may have been deleted while the camera was open.
    @Query("SELECT * FROM track_groups WHERE groupId = :groupId AND userId = :userId LIMIT 1")
    suspend fun getGroupForUser(groupId: Int, userId: Int): TrackGroup?

    /** Where this tracked spot is, from its latest scan that recorded it -- "Scan Again" reuses it
     *  instead of asking again. Null if no scan in the group answered it. */
    @Query("SELECT bodyArea FROM scan_records WHERE groupId = :groupId AND bodyArea != '' ORDER BY scanDate DESC LIMIT 1")
    suspend fun getLastBodyAreaInGroup(groupId: Int): String?

    /** When this group's timeline starts -- the cloud backup's key for the group. */
    @Query("SELECT MIN(scanDate) FROM scan_records WHERE groupId = :groupId")
    suspend fun getGroupStartDate(groupId: Int): Long?

    // ── Bounding boxes ────────────────────────────────────────────────────

    @Insert
    suspend fun insertBoxes(boxes: List<BoundingBox>)

    @Query("DELETE FROM bounding_boxes WHERE scanId = :scanId")
    suspend fun deleteBoxesForScan(scanId: Int)

    @Query("SELECT * FROM bounding_boxes WHERE scanId = :scanId ORDER BY boxId")
    suspend fun getBoxesForScan(scanId: Int): List<BoundingBox>

    /** Detection boxes per scan, for every scan this user owns -- Self Monitoring's spot count. */
    @Query("SELECT b.scanId AS scanId, COUNT(*) AS count FROM bounding_boxes b INNER JOIN scan_records s ON b.scanId = s.scanId INNER JOIN track_groups g ON s.groupId = g.groupId WHERE g.userId = :userId GROUP BY b.scanId")
    suspend fun getBoxCountsByUser(userId: Int): List<ScanBoxCount>

    // ── Check-in triggers ─────────────────────────────────────────────────

    @Insert
    suspend fun insertTriggers(triggers: List<ScanTrigger>)

    @Query("DELETE FROM scan_triggers WHERE scanId = :scanId")
    suspend fun deleteTriggersForScan(scanId: Int)

    @Query("SELECT * FROM scan_triggers WHERE scanId = :scanId ORDER BY triggerId")
    suspend fun getTriggersForScan(scanId: Int): List<ScanTrigger>

    @Query("SELECT t.* FROM scan_triggers t INNER JOIN scan_records s ON t.scanId = s.scanId INNER JOIN track_groups g ON s.groupId = g.groupId WHERE g.userId = :userId")
    suspend fun getTriggersByUser(userId: Int): List<ScanTrigger>
}

data class ScanBoxCount(val scanId: Int, val count: Int)
