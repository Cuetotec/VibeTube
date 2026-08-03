package com.cuetotech.vibetube.data

data class Friend(
    val uid: String,
    val displayName: String,
    val email: String = "",
    val photoUrl: String? = null,
)
