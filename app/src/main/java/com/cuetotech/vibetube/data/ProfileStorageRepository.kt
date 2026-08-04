package com.cuetotech.vibetube.data

import android.net.Uri
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await

// Sube las imágenes de perfil (avatar/banner) a Firebase Storage y devuelve la
// URL de descarga. Cada usuario tiene su propia carpeta (profileImages/{uid}),
// así las reglas de seguridad pueden restringir el acceso al propietario.
class ProfileStorageRepository(
    private val storage: FirebaseStorage = FirebaseStorage.getInstance(),
) {

    suspend fun uploadAvatar(uid: String, uri: Uri): String {
        val reference = storage.reference.child("profileImages/$uid/avatar.jpg")
        reference.putFile(uri).await()
        return reference.downloadUrl.await().toString()
    }

    suspend fun uploadBanner(uid: String, uri: Uri): String {
        val reference = storage.reference.child("profileImages/$uid/banner.jpg")
        reference.putFile(uri).await()
        return reference.downloadUrl.await().toString()
    }
}
