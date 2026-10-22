package com.dermalens.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dermalens.app.data.model.User

@Dao
interface UserDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertUser(user: User): Long

    // COLLATE NOCASE: emails are case-insensitive (Firebase accepts "x@gmail.coM" at login), so an
    // exact match left the saved session email unable to find its own profile row -- Edit Profile
    // showed empty fields and Home/Progress couldn't resolve the account.
    @Query("SELECT * FROM users WHERE email = :email COLLATE NOCASE LIMIT 1")
    suspend fun getUserByEmail(email: String): User?

    @Query("SELECT * FROM users WHERE firebaseUid = :firebaseUid LIMIT 1")
    suspend fun getUserByFirebaseUid(firebaseUid: String): User?

    @Query("UPDATE users SET fullName = :fullName, email = :email WHERE userId = :userId")
    suspend fun updateProfile(userId: Int, fullName: String, email: String)

    @Query("DELETE FROM users WHERE userId = :userId")
    suspend fun deleteUserById(userId: Int)
}