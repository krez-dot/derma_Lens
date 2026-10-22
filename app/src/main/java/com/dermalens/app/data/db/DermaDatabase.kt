package com.dermalens.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dermalens.app.data.model.BoundingBox
import com.dermalens.app.data.model.ScanRecord
import com.dermalens.app.data.model.TrackGroup
import com.dermalens.app.data.model.User

// v8 adds scan_records.boundingBoxes. A real migration, not a wipe: existing scans keep everything
// and just get an empty box list.
private val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE scan_records ADD COLUMN boundingBoxes TEXT NOT NULL DEFAULT ''")
    }
}

// v9 normalizes scan_records to match the ERD (3NF):
//  - the trackGroupId self-reference becomes a track_groups table; each existing timeline keeps
//    its old number as its groupId, and the scan's owner moves onto the group
//  - the "left,top,right,bottom;..." boundingBoxes text becomes one bounding_boxes row per box
//  - severity is dropped (fixed per condition, and nothing displays it)
// Every scan is carried over. The only rows left behind are scans whose user no longer exists,
// which no account could open anyway and which the new foreign keys would reject.
private val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE scan_records RENAME TO scan_records_v8")

        db.execSQL("CREATE TABLE IF NOT EXISTS `track_groups` (`groupId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `userId` INTEGER NOT NULL, FOREIGN KEY(`userId`) REFERENCES `users`(`userId`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_track_groups_userId` ON `track_groups` (`userId`)")
        // A NULL trackGroupId (rows from before timelines existed) was already read as "its own
        // timeline" by the Progress Tracker -- COALESCE keeps that meaning.
        db.execSQL(
            "INSERT INTO track_groups (groupId, userId) " +
                "SELECT COALESCE(trackGroupId, id), MIN(userId) FROM scan_records_v8 " +
                "WHERE userId IN (SELECT userId FROM users) GROUP BY COALESCE(trackGroupId, id)"
        )

        db.execSQL("CREATE TABLE IF NOT EXISTS `scan_records` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `groupId` INTEGER NOT NULL, `condition` TEXT NOT NULL, `confidence` REAL NOT NULL, `notes` TEXT NOT NULL, `scanDate` INTEGER NOT NULL, `imagePath` TEXT NOT NULL, `contributedForTraining` INTEGER NOT NULL, `uploadedForTraining` INTEGER NOT NULL, FOREIGN KEY(`groupId`) REFERENCES `track_groups`(`groupId`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_scan_records_groupId` ON `scan_records` (`groupId`)")
        db.execSQL(
            "INSERT INTO scan_records (id, groupId, condition, confidence, notes, scanDate, imagePath, contributedForTraining, uploadedForTraining) " +
                "SELECT id, COALESCE(trackGroupId, id), condition, confidence, notes, scanDate, imagePath, contributedForTraining, uploadedForTraining " +
                "FROM scan_records_v8 WHERE COALESCE(trackGroupId, id) IN (SELECT groupId FROM track_groups)"
        )

        db.execSQL("CREATE TABLE IF NOT EXISTS `bounding_boxes` (`boxId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `scanId` INTEGER NOT NULL, `left` REAL NOT NULL, `top` REAL NOT NULL, `right` REAL NOT NULL, `bottom` REAL NOT NULL, FOREIGN KEY(`scanId`) REFERENCES `scan_records`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_bounding_boxes_scanId` ON `bounding_boxes` (`scanId`)")
        // Parsed here rather than with the app's own helpers so this migration keeps reading the
        // v8 format exactly, whatever the rest of the code does later. Malformed boxes are skipped,
        // as the v8 reader did.
        db.query("SELECT id, boundingBoxes FROM scan_records_v8 WHERE boundingBoxes != '' AND id IN (SELECT id FROM scan_records)").use { c ->
            while (c.moveToNext()) {
                val scanId = c.getInt(0)
                c.getString(1).split(";").forEach { part ->
                    val v = part.split(",").mapNotNull { it.toFloatOrNull() }
                    if (v.size == 4) {
                        db.execSQL(
                            "INSERT INTO bounding_boxes (scanId, `left`, `top`, `right`, `bottom`) VALUES (?, ?, ?, ?, ?)",
                            arrayOf<Any>(scanId, v[0], v[1], v[2], v[3])
                        )
                    }
                }
            }
        }

        db.execSQL("DROP TABLE scan_records_v8")
    }
}

// v10 drops two dead users columns: passwordHash (always "" since Firebase Auth took over
// passwords) and notificationEnabled (never read -- the reminder switch lives in SharedPreferences).
// SQLite on minSdk 26 has no DROP COLUMN, so the table is rebuilt and renamed into place. Room only
// turns foreign keys on after migrations run, so dropping the old table can't cascade into
// track_groups and wipe anyone's scans.
private val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `users_new` (`userId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `fullName` TEXT NOT NULL, `email` TEXT NOT NULL, `firebaseUid` TEXT, `createdAt` INTEGER NOT NULL)")
        db.execSQL(
            "INSERT INTO users_new (userId, fullName, email, firebaseUid, createdAt) " +
                "SELECT userId, fullName, email, firebaseUid, createdAt FROM users"
        )
        db.execSQL("DROP TABLE users")
        db.execSQL("ALTER TABLE users_new RENAME TO users")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_users_email` ON `users` (`email`)")
    }
}

// v11 makes two changes, each a table rebuild (SQLite can't alter a column's NOT NULL or rename
// one on older Androids). Foreign keys are off during migrations (see v10), and ids are copied
// as-is, so every group, scan and box keeps pointing at its parent.
//  - users.fullName may be NULL: a name is no longer guessed from the email address or the
//    Google profile, it stays empty until the user sets one. Existing names are kept.
//  - scan_records.id is renamed scanId, so every table's key is named after the table (userId,
//    groupId, scanId, boxId). bounding_boxes is rebuilt too, since its foreign key names that column.
private val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `users_new` (`userId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `fullName` TEXT, `email` TEXT NOT NULL, `firebaseUid` TEXT, `createdAt` INTEGER NOT NULL)")
        db.execSQL("INSERT INTO users_new (userId, fullName, email, firebaseUid, createdAt) SELECT userId, fullName, email, firebaseUid, createdAt FROM users")
        db.execSQL("DROP TABLE users")
        db.execSQL("ALTER TABLE users_new RENAME TO users")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_users_email` ON `users` (`email`)")

        db.execSQL("CREATE TABLE IF NOT EXISTS `scan_records_new` (`scanId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `groupId` INTEGER NOT NULL, `condition` TEXT NOT NULL, `confidence` REAL NOT NULL, `notes` TEXT NOT NULL, `scanDate` INTEGER NOT NULL, `imagePath` TEXT NOT NULL, `contributedForTraining` INTEGER NOT NULL, `uploadedForTraining` INTEGER NOT NULL, FOREIGN KEY(`groupId`) REFERENCES `track_groups`(`groupId`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL(
            "INSERT INTO scan_records_new (scanId, groupId, condition, confidence, notes, scanDate, imagePath, contributedForTraining, uploadedForTraining) " +
                "SELECT id, groupId, condition, confidence, notes, scanDate, imagePath, contributedForTraining, uploadedForTraining FROM scan_records"
        )
        db.execSQL("DROP TABLE scan_records")
        db.execSQL("ALTER TABLE scan_records_new RENAME TO scan_records")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_scan_records_groupId` ON `scan_records` (`groupId`)")

        db.execSQL("CREATE TABLE IF NOT EXISTS `bounding_boxes_new` (`boxId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `scanId` INTEGER NOT NULL, `left` REAL NOT NULL, `top` REAL NOT NULL, `right` REAL NOT NULL, `bottom` REAL NOT NULL, FOREIGN KEY(`scanId`) REFERENCES `scan_records`(`scanId`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("INSERT INTO bounding_boxes_new (boxId, scanId, `left`, `top`, `right`, `bottom`) SELECT boxId, scanId, `left`, `top`, `right`, `bottom` FROM bounding_boxes")
        db.execSQL("DROP TABLE bounding_boxes")
        db.execSQL("ALTER TABLE bounding_boxes_new RENAME TO bounding_boxes")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_bounding_boxes_scanId` ON `bounding_boxes` (`scanId`)")
    }
}

@Database(entities = [User::class, TrackGroup::class, ScanRecord::class, BoundingBox::class], version = 11, exportSchema = true)
abstract class DermaDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun scanRecordDao(): ScanRecordDao

    companion object {
        @Volatile
        private var INSTANCE: DermaDatabase? = null

        fun getDatabase(context: Context): DermaDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DermaDatabase::class.java,
                    "dermalens_database"
                )
                    // Versions 1-6 were all pre-launch dev builds (no real user ever ran one, see
                    // docs/HANDOFF.md's DB Version History) -- destructively wiping those specific
                    // upgrades is fine. But blanket fallbackToDestructiveMigration() would also
                    // silently wipe every real user's data on the *next* schema bump after launch
                    // (docs/PRELAUNCH_AUDIT_2026-09-21.md #10). Scoping the fallback to only these
                    // known-dev versions means any future v7->v8+ bump without a real Migration
                    // throws IllegalStateException instead of quietly deleting everything -- a
                    // build-time-visible failure that forces a real migration to be written,
                    // rather than a silent data-loss bug nobody notices until support tickets show up.
                    .fallbackToDestructiveMigrationFrom(1, 2, 3, 4, 5, 6)
                    .addMigrations(MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}