package com.cuetotech.vibetube.data

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.tasks.await

class SongRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {

    suspend fun getSongs(): Result<List<Song>> {
        return try {
            val documents = firestore.collection(COLLECTION_SONGS).get().await()
            Result.success(documents.map { document ->
                Song(
                    id = document.id,
                    youtubeId = document.getString("youtubeId").orEmpty(),
                    title = document.getString("title").orEmpty(),
                    artist = document.getString("artist").orEmpty(),
                    durationSeconds = document.getLong("durationSeconds") ?: 0L,
                )
            })
        } catch (exception: FirebaseFirestoreException) {
            Result.failure(exception)
        }
    }

    private companion object {
        const val COLLECTION_SONGS = "songs"
    }
}
