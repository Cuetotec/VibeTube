package com.cuetotech.vibetube.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

// Guarda las imágenes de perfil (avatar y fondo) en la carpeta privada de la
// app (Internal Storage, context.filesDir). No se usa Firebase Storage: la
// imagen elegida de la galería se copia a filesDir y se devuelve su ruta local
// ("file:///...") para guardarla en el perfil (Firestore) y cargarla en la UI.
class ProfileMediaRepository(
    appContext: Context,
) {
    private val appContext: Context = appContext.applicationContext

    // Copia la imagen seleccionada (Uri de la galería, content://...) a la
    // carpeta privada de la app y devuelve la ruta local "file:///...".
    suspend fun copyImageToPrivateStorage(source: Uri, fileName: String): String =
        withContext(Dispatchers.IO) {
            val dest = File(appContext.filesDir, fileName)
            appContext.contentResolver.openInputStream(source)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IOException("No se pudo abrir la imagen seleccionada")
            dest.toURI().toString()
        }

    // Elimina una imagen local previa (file://...) si está dentro de filesDir,
    // para no acumular archivos huérfanos al reemplazar avatar/fondo.
    fun deleteLocalImage(fileUrl: String?) {
        if (fileUrl.isNullOrBlank()) return
        val path = runCatching { Uri.parse(fileUrl).path }.getOrNull() ?: return
        val file = File(path)
        val filesDir = appContext.filesDir
        val base = runCatching { filesDir.canonicalPath }.getOrNull() ?: return
        val filePath = runCatching { file.canonicalPath }.getOrNull() ?: return
        if (file.exists() && filePath.startsWith(base)) {
            file.delete()
        }
    }

    // Devuelve la URL solo si sigue siendo válida para la UI:
    //  - URL remota (https:// de la antigua Firebase Storage): se conserva.
    //  - Ruta local (file://...) cuyo archivo ya no existe en disco (app
    //    reinstalada, archivo borrado): se devuelve null para que la UI
    //    muestre la imagen por defecto (iniciales) sin romper el flujo.
    fun existingLocalImage(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return url
        if (uri.scheme != "file") return url
        val path = uri.path ?: return null
        return if (File(path).exists()) url else null
    }
}