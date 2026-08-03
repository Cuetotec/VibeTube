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
            photoUrl = data["photoUrl"] as? String,
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
                "photoUrl" to (profile.photoUrl ?: ""),
            ),
        ).await()
    }

    suspend fun searchUsers(query: String, excludeUid: String? = null, limit: Int = 20): List<UserProfile> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val snapshot = firestore.collection(USERS_COLLECTION)
            .orderBy("displayName")
            .limit(100)
            .get().await()
        return snapshot.documents
            .mapNotNull { it.toUserProfile() }
            .filter { it.uid != excludeUid }
            .filter { user ->
                user.displayName.contains(trimmed, ignoreCase = true) ||
                    user.email.contains(trimmed, ignoreCase = true)
            }
            .take(limit)
    }

    private fun com.google.firebase.firestore.DocumentSnapshot.toUserProfile(): UserProfile? {
        val data = data ?: return null
        val uid = data["uid"] as? String ?: id
        val displayName = data["displayName"] as? String ?: return null
        return UserProfile(
            uid = uid,
            displayName = displayName,
            email = data["email"] as? String ?: "",
            avatarUrl = data["avatarUrl"] as? String,
            bannerUrl = data["bannerUrl"] as? String,
            photoUrl = data["photoUrl"] as? String,
        )
    }

    private companion object {
        const val USERS_COLLECTION = "users"
    }
}
