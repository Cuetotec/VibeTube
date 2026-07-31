package com.cuetotech.vibetube.data

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

class UserProfileRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {

    suspend fun getUserProfile(uid: String): UserProfile? {
        val document = firestore.collection(USERS_COLLECTION).document(uid).get().await()
        if (!document.exists()) return null
        val data = document.data ?: return null
        return UserProfile(
            uid = uid,
            displayName = data["displayName"] as? String ?: "",
            email = data["email"] as? String ?: "",
            avatarUrl = data["avatarUrl"] as? String,
            bannerUrl = data["bannerUrl"] as? String,
        )
    }

    suspend fun saveUserProfile(profile: UserProfile) {
        firestore.collection(USERS_COLLECTION).document(profile.uid).set(
            mapOf(
                "uid" to profile.uid,
                "displayName" to profile.displayName,
                "email" to profile.email,
                "avatarUrl" to (profile.avatarUrl ?: ""),
                "bannerUrl" to (profile.bannerUrl ?: ""),
            ),
        ).await()
    }

    private companion object {
        const val USERS_COLLECTION = "users"
    }
}
