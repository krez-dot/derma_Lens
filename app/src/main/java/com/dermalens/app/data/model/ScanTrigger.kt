package com.dermalens.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One possible trigger the user ticked in a scan's pre-scan check-in ("Heat or sweating", ...).
 * Its own table rather than a comma list on scan_records, same as bounding_boxes, so the schema
 * stays in 1NF and Insights can count triggers with a plain query.
 */
@Entity(
    tableName = "scan_triggers",
    foreignKeys = [ForeignKey(
        entity = ScanRecord::class,
        parentColumns = ["scanId"],
        childColumns = ["scanId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("scanId")]
)
data class ScanTrigger(
    @PrimaryKey(autoGenerate = true)
    val triggerId: Int = 0,
    val scanId: Int,
    // Not "trigger": that's an SQL keyword.
    val triggerName: String
)
