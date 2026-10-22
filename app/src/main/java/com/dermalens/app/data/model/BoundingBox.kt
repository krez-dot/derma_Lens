package com.dermalens.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One detection box drawn on a saved scan's photo, so the scan reopens with its boxes (FR16).
 * Coordinates are fractions (0-1) of the photo. Only saved alongside a photo -- boxes with no
 * image to draw on mean nothing -- so scans restored from the cloud backup have none.
 */
@Entity(
    tableName = "bounding_boxes",
    foreignKeys = [ForeignKey(
        entity = ScanRecord::class,
        parentColumns = ["scanId"],
        childColumns = ["scanId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("scanId")]
)
data class BoundingBox(
    @PrimaryKey(autoGenerate = true)
    val boxId: Int = 0,
    val scanId: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)
