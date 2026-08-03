package com.cuetotech.vibetube.data

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class FriendshipRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {

    suspend fun sendFriendRequest(me: UserProfile, target: UserProfile): SendRequestResult {
        val alreadyFriends = firestore.collection(USERS_COLLECTION)
            .document(me.uid)
            .collection(FRIENDS_SUBCOLLECTION)
            .document(target.uid)
            .get().await()
            .exists()
        if (alreadyFriends) return SendRequestResult.AlreadyFriends

        val reversePending = firestore.collection(REQUESTS_COLLECTION)
            .whereEqualTo("fromUid", target.uid)
            .get().await()
            .documents
            .firstOrNull { doc ->
                doc.get("toUid") == me.uid && doc.get("status") == STATUS_PENDING
            }
        if (reversePending != null) {
            createFriendship(
                me,
                Friend(
                    uid = target.uid,
                    displayName = target.displayName,
                    email = target.email,
                    photoUrl = target.photoUrl,
                ),
            )
            reversePending.reference.update("status", STATUS_ACCEPTED).await()
            return SendRequestResult.AcceptedAutomatically
        }

        val existing = firestore.collection(REQUESTS_COLLECTION)
            .whereEqualTo("fromUid", me.uid)
            .get().await()
            .documents
            .firstOrNull { doc ->
                doc.get("toUid") == target.uid &&
                    (doc.get("status") == STATUS_PENDING || doc.get("status") == STATUS_ACCEPTED)
            }
        if (existing != null) return SendRequestResult.AlreadyPending

        firestore.collection(REQUESTS_COLLECTION).add(
            mapOf(
                "fromUid" to me.uid,
                "toUid" to target.uid,
                "fromName" to me.displayName,
                "fromPhotoUrl" to (me.photoUrl ?: ""),
                "status" to STATUS_PENDING,
                "createdAt" to System.currentTimeMillis(),
            ),
        ).await()
        return SendRequestResult.Sent
    }

    suspend fun acceptRequest(me: UserProfile, request: IncomingFriendRequest) {
        createFriendship(
            me,
            Friend(
                uid = request.fromUid,
                displayName = request.fromDisplayName,
                photoUrl = request.fromPhotoUrl,
            ),
        )
        firestore.collection(REQUESTS_COLLECTION).document(request.requestId)
            .update("status", STATUS_ACCEPTED)
            .await()
    }

    suspend fun rejectRequest(requestId: String) {
        firestore.collection(REQUESTS_COLLECTION).document(requestId)
            .update("status", STATUS_REJECTED)
            .await()
    }

    suspend fun cancelRequest(requestId: String) {
        firestore.collection(REQUESTS_COLLECTION).document(requestId)
            .delete().await()
    }

    suspend fun removeFriend(meUid: String, friendUid: String) {
        firestore.collection(USERS_COLLECTION).document(meUid)
            .collection(FRIENDS_SUBCOLLECTION).document(friendUid)
            .delete().await()
        firestore.collection(USERS_COLLECTION).document(friendUid)
            .collection(FRIENDS_SUBCOLLECTION).document(meUid)
            .delete().await()
    }

    fun observeIncomingRequests(uid: String): Flow<List<IncomingFriendRequest>> = callbackFlow {
        val listener = firestore.collection(REQUESTS_COLLECTION)
            .whereEqualTo("toUid", uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val requests = snapshot?.documents.orEmpty()
                    .filter { it.get("status") == STATUS_PENDING }
                    .mapNotNull { it.toIncomingFriendRequest() }
                    .sortedByDescending { it.createdAt }
                trySend(requests)
            }
        awaitClose { listener.remove() }
    }

    fun observeFriends(uid: String): Flow<List<Friend>> = callbackFlow {
        val listener = firestore.collection(USERS_COLLECTION)
            .document(uid)
            .collection(FRIENDS_SUBCOLLECTION)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val friends = snapshot?.documents.orEmpty()
                    .mapNotNull { doc ->
                        doc.data ?: return@mapNotNull null
                        Friend(
                            uid = doc.id,
                            displayName = doc.getString("displayName") ?: doc.id,
                            email = doc.getString("email") ?: "",
                            photoUrl = doc.getString("photoUrl"),
                        )
                    }
                    .sortedBy { it.displayName.lowercase() }
                trySend(friends)
            }
        awaitClose { listener.remove() }
    }

    private suspend fun createFriendship(me: UserProfile, other: Friend) {
        val now = System.currentTimeMillis()
        firestore.collection(USERS_COLLECTION).document(me.uid)
            .collection(FRIENDS_SUBCOLLECTION).document(other.uid)
            .set(
                mapOf(
                    "displayName" to other.displayName,
                    "email" to other.email,
                    "photoUrl" to (other.photoUrl ?: ""),
                    "addedAt" to now,
                ),
            ).await()
        firestore.collection(USERS_COLLECTION).document(other.uid)
            .collection(FRIENDS_SUBCOLLECTION).document(me.uid)
            .set(
                mapOf(
                    "displayName" to me.displayName,
                    "email" to me.email,
                    "photoUrl" to (me.photoUrl ?: ""),
                    "addedAt" to now,
                ),
            ).await()
    }

    private fun DocumentSnapshot.toIncomingFriendRequest(): IncomingFriendRequest? {
        val data = data ?: return null
        val fromUid = data["fromUid"] as? String ?: return null
        return IncomingFriendRequest(
            requestId = id,
            fromUid = fromUid,
            fromDisplayName = data["fromName"] as? String ?: fromUid,
            fromPhotoUrl = data["fromPhotoUrl"] as? String,
            createdAt = data["createdAt"] as? Long ?: 0L,
        )
    }

    enum class SendRequestResult {
        Sent,
        AlreadyPending,
        AlreadyFriends,
        AcceptedAutomatically,
    }

    private companion object {
        const val USERS_COLLECTION = "users"
        const val FRIENDS_SUBCOLLECTION = "friends"
        const val REQUESTS_COLLECTION = "friend_requests"
        const val STATUS_PENDING = "pending"
        const val STATUS_ACCEPTED = "accepted"
        const val STATUS_REJECTED = "rejected"
    }
}
