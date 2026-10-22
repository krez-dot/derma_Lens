package com.dermalens.app.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "users",
    indices = [Index(value = ["email"], unique = true)]
)
data class User(
    @PrimaryKey(autoGenerate = true)
    val userId: Int = 0,
    // Null until the user sets a name in Register or Edit Profile -- never guessed from the email
    // address or the Google profile.
    val fullName: String? = null,
    val email: String,
    // The password itself lives in Firebase Auth, never here.
    val firebaseUid: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)