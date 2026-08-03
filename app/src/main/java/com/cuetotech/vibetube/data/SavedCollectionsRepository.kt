package com.cuetotech.vibetube.data

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class SavedCollectionsRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {

    suspend fun saveCollection(uid: String, collection: SavedCollection) {
        firestore.collection(USERS_COLLECTION).document(uid)
            .collection(SAVED_COLLECTIONS_SUBCOLLECTION).document(collection.playlistId)
            .set(
                mapOf(
                    "playlistId" to collection.playlistId,
                    "ownerId" to collection.ownerId,
                    "ownerDisplayName" to collection.ownerDisplayName,
                    "ownerPhotoUrl" to (collection.ownerPhotoUrl ?: ""),
                    "savedAt" to System.currentTimeMillis(),
                ),
            ).await()
    }

    suspend fun removeCollection(uid: String, playlistId: String) {
        firestore.collection(USERS_COLLECTION).document(uid)
            .collection(SAVED_COLLECTIONS_SUBCOLLECTION).document(playlistId)
            .delete().await()
    }

    fun observeSavedCollections(uid: String): Flow<List<SavedCollection>> = callbackFlow {
        val listener = firestore.collection(USERS_COLLECTION).document(uid)
            .collection(SAVED_COLLECTIONS_SUBCOLLECTION)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val collections = snapshot?.documents.orEmpty().mapNotNull { doc ->
                    val data = doc.data ?: return@mapNotNull null
                    val ownerId = data["ownerId"] as? String ?: return@mapNotNull null
                    SavedCollection(
                        playlistId = doc.id,
                        ownerId = ownerId,
                        ownerDisplayName = data["ownerDisplayName"] as? String ?: ownerId,
                        ownerPhotoUrl = data["ownerPhotoUrl"] as? String,
                        savedAt = data["savedAt"] as? Long ?: 0L,
                    )
                }.sortedByDescending { it.savedAt }
                trySend(collections)
            }
        awaitClose { listener.remove() }
    }

    private companion object {
        const val USERS_COLLECTION = "users"
        const val SAVED_COLLECTIONS_SUBCOLLECTION = "savedCollections"
    }
}
