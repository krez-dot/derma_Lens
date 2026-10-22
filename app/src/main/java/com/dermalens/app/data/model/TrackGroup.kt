package com.dermalens.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One tracked spot -- the timeline the Progress Tracker draws as a single card. "Start New Scan"
 * creates a group; "Scan Again" on a card saves into that card's group. Keeps two unrelated
 * occurrences that classify the same (a wart on one finger, a new wart on a toe) apart.
 *
 * Replaces the old scan_records.trackGroupId self-reference (v8 and earlier), which had to be
 * NULL until its own row's id existed and was left pointing at nothing once the first scan of a
 * timeline was deleted.
 */
@Entity(
    tableName = "track_groups",
    foreignKeys = [ForeignKey(
        entity = User::class,
        parentColumns = ["userId"],
        childColumns = ["userId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("userId")]
)
data class TrackGroup(
    @PrimaryKey(autoGenerate = true)
    val groupId: Int = 0,
    val userId: Int
)
