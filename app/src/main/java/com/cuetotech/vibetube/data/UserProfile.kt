package com.cuetotech.vibetube.data

data class UserProfile(
    val uid: String,
    val displayName: String,
    val email: String,
    val avatarUrl: String? = null,
    val bannerUrl: String? = null,
)
