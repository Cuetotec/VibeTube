package com.cuetotech.vibetube.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class FavoritesRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {

    fun currentUser(): FirebaseUser? = auth.currentUser

    suspend fun ensureSignedIn(): String {
        auth.currentUser?.let { return it.uid }
        val user = auth.signInAnonymously().await().user
        return user?.uid ?: error("No se pudo completar el inicio de sesión anónimo")
    }

    fun observeFavorites(userId: String): Flow<List<Song>> = callbackFlow {
        val listener = userFavoritesCollection(userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val songs = snapshot?.documents.orEmpty().mapNotNull { it.toSong() }
                trySend(songs)
            }
        awaitClose { listener.remove() }
    }

    suspend fun addFavorite(song: Song) {
        val userId = ensureSignedIn()
        userFavoritesCollection(userId).document(song.id).set(song).await()
    }

    suspend fun removeFavorite(songId: String) {
        val userId = ensureSignedIn()
        userFavoritesCollection(userId).document(songId).delete().await()
    }

    private fun userFavoritesCollection(userId: String) =
        firestore.collection(USERS_COLLECTION)
            .document(userId)
            .collection(FAVORITES_COLLECTION)

    private fun DocumentSnapshot.toSong(): Song? {
        val data = data ?: return null
        val youtubeId = data["youtubeId"] as? String ?: return null
        val title = data["title"] as? String ?: return null
        val artist = data["artist"] as? String ?: return null
        return Song(
            id = data["id"] as? String ?: id,
            youtubeId = youtubeId,
            title = title,
            artist = artist,
            durationSeconds = (data["durationSeconds"] as? Number)?.toLong() ?: 0L,
        )
    }

    private companion object {
        const val USERS_COLLECTION = "users"
        const val FAVORITES_COLLECTION = "favorites"
    }
}
