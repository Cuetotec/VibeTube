# Memoria del proyecto — VibeTube

Reproductor de música para Android (Kotlin + Jetpack Compose) que reproduce vídeos de YouTube y sincroniza los favoritos del usuario con Firebase Firestore.

## Estado actual (avances hasta ahora)

### Arquitectura
- Patrón MVVM: capa `data/` (repositorios y modelos), capa `ui/` (pantallas Compose + ViewModels).
- El reproductor se encuentra en el paquete `com.cuetotech.vibetube.player`.
- Tema oscuro estilo YouTube Music/Spotify: fondo `#0F0F0F`, superficies `#121212`/`#1E1E1E`, acento rojo `#FF0033`.

### Dependencias
- `firebase-firestore` y `firebase-auth` gestionadas por `firebase-bom` (34.16.0) en `gradle/libs.versions.toml` y `app/build.gradle.kts`.
- `androidx-compose-material-icons-core` para los iconos (no se usa `material-icons-extended`).

### Catálogo de canciones (`SongRepository`)
- Lee la colección `songs` de Firestore con `FirebaseFirestore.getInstance()` + `tasks.await()`.
- `getSongs()` mapea cada documento a `Song(id, youtubeId, title, artist, durationSeconds)`.

### Búsqueda y reproductor (pantalla de inicio)
- Búsqueda en tiempo real sobre `title`/`artist` con `contains(ignoreCase = true)`.
- El reproductor carga por defecto la primera canción de la lista; al seleccionar una canción, cambia el vídeo.

### Galería / Mi Colección (favoritos sincronizados con Firestore)
- `FavoritesRepository`:
  - Login anónimo con Firebase (`signInAnonymously`).
  - Colección `users/{userId}/favorites/{songId}` en Firestore.
  - CRUD: `addFavorite(song)` y `removeFavorite(songId)`.
  - Listener en tiempo real (`addSnapshotListener`) expuesto como `Flow<List<Song>>`.
- `CollectionViewModel`:
  - Estado `Loading / Success / Error` con reintento.
  - Expone `favoriteIds: StateFlow<Set<String>>` actualizado en tiempo real.
  - `toggleFavorite(song)` para añadir/quitar favoritos.
- `CollectionScreen` ("Mi Colección"):
  - Muestra la lista de canciones favoritas.
  - Estado vacío con mensaje guía.
  - Botón de corazón para quitar de la colección.
- `YouTubePlayerView`:
  - Overlay de corazón sobre el reproductor (relleno si es favorito, contorno si no).
- `SongItem`:
  - Nuevo slot `trailingContent` para añadir el corazón a cada canción de la lista.
- `HomeScreen`:
  - Corazón en el reproductor y en cada canción de la lista; refleja el estado en tiempo real.
- `MainActivity`:
  - Barra de navegación inferior con dos pestañas: **Inicio** y **Mi Colección**.
- `strings.xml`: recursos para pestañas, colección vacía, y acciones de favorito.

### Comandos útiles
- Build: `./gradlew :app:assembleDebug`
- Tests unitarios: `./gradlew :app:testDebugUnitTest`

## Pendiente / ideas
- (por definir en próximas iteraciones)
