package com.cuetotech.vibetube.data

data class IncomingFriendRequest(
    val requestId: String,
    val fromUid: String,
    val fromDisplayName: String,
    val fromPhotoUrl: String? = null,
    val createdAt: Long = 0L,
)
