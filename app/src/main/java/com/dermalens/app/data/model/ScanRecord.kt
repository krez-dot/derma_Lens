package com.dermalens.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "scan_records",
    foreignKeys = [ForeignKey(
        entity = TrackGroup::class,
        parentColumns = ["groupId"],
        childColumns = ["groupId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("groupId")]
)
data class ScanRecord(
    @PrimaryKey(autoGenerate = true)
    val scanId: Int = 0,
    // The tracked spot this scan belongs to (see TrackGroup). The owning user is reached through
    // the group rather than stored here too, so the two can never disagree.
    val groupId: Int,
    val condition: String,
    val confidence: Float,
    val notes: String = "",
    val scanDate: Long = System.currentTimeMillis(),
    val imagePath: String = "",
    val contributedForTraining: Boolean = false,
    // True once ContributionUploadWorker has successfully uploaded this scan's image to Google
    // Drive (via the Apps Script endpoint) -- separate from contributedForTraining (which just means "user consented and the
    // image was saved locally, pending upload") so the worker knows what's left to do.
    val uploadedForTraining: Boolean = false
)
