package com.cuetotech.vibetube.data

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class PlaylistRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {

    suspend fun createPlaylist(
        ownerId: String,
        title: String,
        description: String,
        isPublic: Boolean,
    ): String {
        val document = firestore.collection(PLAYLISTS_COLLECTION).document()
        document.set(
            mapOf(
                "ownerId" to ownerId,
                "title" to title.trim(),
                "description" to description.trim(),
                "isPublic" to isPublic,
                "tracks" to emptyList<Map<String, Any>>(),
                "createdAt" to System.currentTimeMillis(),
            ),
        ).await()
        return document.id
    }

    fun observeUserPlaylists(ownerId: String): Flow<List<Playlist>> = callbackFlow {
        val listener = firestore.collection(PLAYLISTS_COLLECTION)
            .whereEqualTo("ownerId", ownerId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val playlists = snapshot?.documents.orEmpty().mapNotNull { it.toPlaylist() }
                trySend(playlists)
            }
        awaitClose { listener.remove() }
    }

    fun observePublicPlaylists(ownerId: String): Flow<List<Playlist>> =
        observeUserPlaylists(ownerId).filterPublic()

    fun observePublicPlaylist(playlistId: String): Flow<Playlist?> = callbackFlow {
        val listener = firestore.collection(PLAYLISTS_COLLECTION)
            .document(playlistId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val playlist = snapshot?.let { document ->
                    if (document.exists()) document.toPlaylist() else null
                }?.takeIf { it.isPublic }
                trySend(playlist)
            }
        awaitClose { listener.remove() }
    }

    suspend fun getPlaylist(playlistId: String): Playlist? {
        val document = firestore.collection(PLAYLISTS_COLLECTION).document(playlistId).get().await()
        return document.takeIf { it.exists() }?.toPlaylist()
    }

    private fun Flow<List<Playlist>>.filterPublic(): Flow<List<Playlist>> =
        map { playlists -> playlists.filter { it.isPublic } }

    suspend fun addTrack(playlistId: String, song: Song) {
        firestore.collection(PLAYLISTS_COLLECTION).document(playlistId)
            .update("tracks", FieldValue.arrayUnion(song.toFirestoreMap()))
            .await()
    }

    suspend fun removeTrack(playlistId: String, songId: String) {
        val reference = firestore.collection(PLAYLISTS_COLLECTION).document(playlistId)
        val snapshot = reference.get().await()
        val tracks = (snapshot.get("tracks") as? List<*>)
            ?.filterIsInstance<Map<*, *>>()
            ?.filter { (it["id"] as? String) != songId }
            ?: emptyList<Map<*, *>>()
        reference.update("tracks", tracks).await()
    }

    suspend fun deletePlaylist(playlistId: String) {
        firestore.collection(PLAYLISTS_COLLECTION).document(playlistId).delete().await()
    }

    private fun Song.toFirestoreMap(): Map<String, Any> = mapOf(
        "id" to id,
        "youtubeId" to youtubeId,
        "title" to title,
        "artist" to artist,
        "durationSeconds" to durationSeconds,
    )

    private fun DocumentSnapshot.toPlaylist(): Playlist? {
        val data = data ?: return null
        val ownerId = data["ownerId"] as? String ?: return null
        val title = data["title"] as? String ?: return null
        val tracks = (data["tracks"] as? List<*>)
            ?.filterIsInstance<Map<*, *>>()
            .orEmpty()
            .map { map ->
                Song(
                    id = map["id"] as? String ?: "",
                    youtubeId = map["youtubeId"] as? String ?: "",
                    title = map["title"] as? String ?: "",
                    artist = map["artist"] as? String ?: "",
                    durationSeconds = (map["durationSeconds"] as? Number)?.toLong() ?: 0L,
                )
            }
        return Playlist(
            id = id,
            ownerId = ownerId,
            title = title,
            description = data["description"] as? String ?: "",
            isPublic = data["isPublic"] as? Boolean ?: false,
            tracks = tracks,
            createdAt = data["createdAt"] as? Long ?: 0L,
        )
    }

    private companion object {
        const val PLAYLISTS_COLLECTION = "playlists"
    }
}
