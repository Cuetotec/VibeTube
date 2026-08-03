# Memoria del proyecto — VibeTube

Plataforma de música personalizada y social para Android (Kotlin + Jetpack Compose) que reproduce vídeos de YouTube y sincroniza usuarios, perfiles y listas de reproducción con Firebase.

## Estado actual (avances hasta ahora)

### Arquitectura
- Patrón MVVM: capa `data/` (modelos y repositorios), capa `ui/` (pantallas Compose + ViewModels).
- El reproductor se encuentra en el paquete `com.cuetotech.vibetube.player`.
- ViewModels compartidos por ámbito de Activity (`viewModel()`), lo que permite compartir estado entre pestañas y diálogos.
- Tema oscuro estilo YouTube Music/Spotify: fondo `#0F0F0F`, superficies `#121212`/`#1E1E1E`, acento rojo `#FF0033`.

### Dependencias
- `firebase-firestore`, `firebase-auth` (gestionadas por `firebase-bom` 34.16.0).
- `coil-compose` (2.7.0) para imágenes remotas (miniaturas, avatar, banner).
- `androidx-compose-material-icons-core` y `material-icons-extended`.
- `kotlinx-coroutines-play-services` para `tasks.await()`.
- **Eliminada** `androidyoutubeplayer-core` (librería pesada de `pierfrancescosoffritti`): el reproductor ahora usa el `WebView` estándar de Android.

### Autenticación (Email + Contraseña)
- `AuthRepository`: `signIn`, `signUp`, `signOut` y `authState()` (Flow en tiempo real del usuario actual).
- `AuthViewModel`: formulario Login/Registro con validación y errores mapeados (email ya registrado, credenciales incorrectas, etc.).
- `AuthScreen`: toggle Login/Registro, nombre (solo registro), mostrar/ocultar contraseña, estado de carga.
- El perfil se crea automáticamente en Firestore al registrar (y si no existe al iniciar sesión).

### Perfil de usuario (`UserProfile`)
- Documento `users/{uid}` con `uid`, `displayName`, `email`, `avatarUrl`, `bannerUrl`, `photoUrl`.
- `UserProfileRepository`: `getUserProfile(uid)`, `saveUserProfile(profile)` y `searchUsers(query, excludeUid, limit)` (búsqueda local de la colección `users` por nombre o email).

### Pantalla principal (Inicio)
- Cabecera de perfil compacta: banner/portada (foto o gradiente), avatar (foto o iniciales), nombre y email, con botón de cerrar sesión.
- **Buscador de YouTube** en la parte principal: búsqueda por nombre de canciones o vídeos.
- Cada resultado incluye un botón **'+'** que abre el diálogo para elegir en qué lista guardarlo.
- Acción adicional "Por enlace" para pegar una URL de YouTube directamente.
- (Eliminados del Inicio: la sección de 'Mis listas' y el botón 'Crear nueva lista'.)

### Búsqueda de YouTube (API Data v3)
- `YouTubeSearchRepository`:
  - `search(query)`: usa el endpoint `search.list` para obtener vídeo, título y canal.
  - Segundo lote de llamadas `videos.list` (part `contentDetails`) para la duración (ISO 8601 → segundos).
  - La API key se lee de `local.properties` (`YOUTUBE_API_KEY`) y se expone vía `BuildConfig.YOUTUBE_API_KEY` (`buildConfig = true`).
  - Si no hay key configurada, muestra el error correspondiente.
- Se ha **eliminado el catálogo de prueba** (`songs` de Firestore y `SongRepository`/`SongViewModel`); ya no hay vídeos/canciones precargadas.

### Gestión de Listas de reproducción (playlists)
- Colección `playlists/{playlistId}` con `ownerId`, `title`, `description`, `isPublic`, `tracks` (array de canciones) y `createdAt`.
- `PlaylistRepository`: `createPlaylist`, `deletePlaylist`, `observeUserPlaylists` (snapshot en tiempo real), `addTrack` (`arrayUnion`, sin duplicados) y `removeTrack`.
- `PlaylistsViewModel` (compartido):
  - Observa las listas del usuario actual (`flatMapLatest` al cambiar de usuario).
  - Estado de reproducción: `selectedPlaylistId` y `selectedTrackId`.
  - Diálogos: canción pendiente de añadir, nueva lista, enlace URL.

### Pantalla Mis Colecciones
- **Dos secciones** dentro de la pestaña Mis Colecciones:
  - **Mis listas**: listas propias con título, descripción, nº de canciones, badge Pública/Privada, botón eliminar y botón **Nueva** (crear lista). **La lista general NO instancia ningún reproductor de vídeo**: solo texto/tarjetas ligeras.
  - **Colecciones guardadas**: listas públicas de amigos guardadas, etiquetadas con "Lista de {nombre}", con botón para quitarlas. Si solo hay colecciones guardadas se muestra un enlace a la pestaña **Amigos**.
- Al pulsar una lista se abre su **detalle con el reproductor integrado**:
  - El reproductor reproduce la primera canción (o la seleccionada).
  - Tocar una canción la reproduce al instante (se resalta en color primario).
  - Cada canción tiene botón para quitarla de la lista (el reproductor pasa a la siguiente).
  - El botón flotante del reproductor permite añadir la canción en reproducción a otra lista (**solo en listas propias**; en colecciones guardadas el detalle es de solo lectura, sin quitar canciones ni botón '+', y con opción de dejar de seguir la colección).
  - `BackHandler` vuelve al listado.
- **Robustez / anti-crash**:
  - **Reproductor ligero (WebView estándar)**: `player/YouTubePlayerView.kt` carga el embed `https://www.youtube.com/embed/{videoId}?autoplay=1` en un `android.webkit.WebView` único (sin librerías externas). `javaScriptEnabled = true`, `domStorageEnabled` y `mediaPlaybackRequiresUserGesture=false`; `WebViewClient` + `WebChromeClient`; fondo negro.
  - **Evita Error 150/153/152**: la causa raíz era usar como origen/base del documento `https://www.youtube.com` (el iframe del embed quedaba en el **mismo origen** que YouTube → rechazado como configuración inválida). Ahora el origen es **propio**: `PLAYER_ORIGIN = "https://${BuildConfig.APPLICATION_ID}"`, usado como base URL de `loadDataWithBaseURL` y como `playerVars.origin`. Se añade `<meta name="referrer" content="strict-origin-when-cross-origin">` y `playsinline: 1`. Así el WebView envía `Referer: https://com.cuetotech.vibetube` que coincide con `origin`.
  - **Gestión de errores robusta**: el JS hace `window.VibeTubeBridge.onPlayerError(code)` (vía `addJavascriptInterface`) y Compose muestra un **Toast** + overlay con el mensaje "Este vídeo no se puede reproducir dentro de la app" y un botón **"Abrir en YouTube"** (`ACTION_VIEW` con `https://www.youtube.com/watch?v={id}`). El overlay sustituye a la pantalla negra y la interfaz sigue respondiendo.
  - **Error 152 / autoplay**: `mediaPlaybackRequiresUserGesture = false`; cualquier error (100/101/150/152/152-4/153...) activa **directamente** el overlay de error sin reintentar en bucle.
  - **Caché/historial**: el WebView usa `settings.cacheMode = WebSettings.LOAD_NO_CACHE` y antes de cargar el embed hace `clearCache(true)` + `clearHistory()` para evitar contenido cacheado corrupto.
  - **User-Agent normalizado**: el WebView elimina el marcador `; wv` y fija `Chrome/120.0.0.0` + `Version/1.0` para que YouTube no identifique el entorno como WebView embebido no soportado.
  - **Creación única por detalle**: el contenedor (`FrameLayout`) se crea en el `factory` de la `AndroidView` (una vez por composición) y la referencia vive en `remember { PlayerRef() }`; el `WebView` se añade como hijo dentro de `DisposableEffect(videoId)`, por lo que **solo se (re)crea/carga cuando cambia el `videoId`**, nunca en cada recomposición.
  - **Liberación al salir**: `onDispose` del `DisposableEffect(videoId)` hace `stopLoading()` + `loadUrl("about:blank")` + desattach + `destroy()` envuelto en `runCatching`, tanto al salir de la pantalla como al cambiar de vídeo; la creación del `WebView` va en `runCatching` (ante un fallo se queda el contenedor negro).
  - **Lazy loading estricto**: el reproductor solo se instancia cuando la lista tiene vídeos y `selectedTrackId` resuelve a una canción con `youtubeId.isNotBlank()`. Si la lista está vacía → **"Esta lista no tiene vídeos añadidos"**; si tiene vídeos pero no hay selección → **"Selecciona un vídeo para reproducir"** (nunca se renderiza el reproductor).
  - **Dimensiones fijas**: `Box` del reproductor con `fillMaxWidth().height(220.dp)`.
  - **Click en la lista**: `SongItem` → `onSelectTrack` → `selectTrack(id)` del ViewModel actualiza `selectedTrackId` y el reproductor carga el nuevo vídeo.
  - **ViewModel blindado**: `observePlaylists` guarda el `Job` y lo cancela antes de lanzar uno nuevo (`observeJob?.cancel()`), evitando acumular listeners de Firestore si se pulsa "Reintentar" varias veces; usa `flatMapLatest` y `distinctUntilChanged`, `try-catch` reintentando `CancellationException`. `_uiState` es un `data class` (dedupe por igualdad en `StateFlow` → sin recomposiciones redundantes).
- Los diálogos (añadir a lista, nueva lista, enlace URL) se muestran a nivel de `MainActivity`.

### Amigos y red social
- **Solicitudes de amistad** (`friend_requests/{requestId}`): documento con `fromUid`, `toUid`, `fromName`, `fromPhotoUrl`, `status` (`pending`/`accepted`/`rejected`) y `createdAt`. Consultas por `whereEqualTo("toUid", uid)` / `whereEqualTo("fromUid", uid)` filtrando `status` en cliente (evita índices compuestos).
- **Amigos** (`users/{uid}/friends/{friendUid}`): snapshot del amigo (`displayName`, `email`, `photoUrl`, `addedAt`) escrito en **ambas** direcciones al aceptar; `removeFriend` borra ambos documentos.
- `FriendshipRepository`: `sendFriendRequest` (detecta "ya amigos", "ya pendiente" y **auto-acepta** si existe solicitud inversa), `acceptRequest`, `rejectRequest`, `removeFriend`, `observeIncomingRequests`, `observeFriends`.
- **Pestaña Amigos** (`FriendsScreen` + `FriendsViewModel`):
  - Buscador de personas por nombre o email (debounce 350ms) con botón **Añadir** → envía solicitud.
  - **Solicitudes recibidas** con botones Aceptar/Rechazar.
  - **Lista de amigos** → al tocarla abre el **perfil público** (con `BackHandler`).
  - `AppSnackbarMessages` (componente compartido en `ui/components/`) muestra los mensajes de estado.
- **Perfil público** (`FriendProfileScreen` + `FriendProfileViewModel`): avatar (foto o iniciales), nombre, email y **listas públicas** del amigo (`observePublicPlaylists` reusa `observeUserPlaylists` filtrando `isPublic` en cliente). Cada lista tiene botón Guardar/Quitar (bookmark).
- **Colecciones guardadas** (`users/{uid}/savedCollections/{playlistId}`): referencia `{playlistId, ownerId, ownerDisplayName, ownerPhotoUrl, savedAt}`. `SavedCollectionsRepository`: `saveCollection`, `removeCollection`, `observeSavedCollections`.
- `PlaylistsViewModel` combina las colecciones guardadas con el contenido real de cada lista vía `observePublicPlaylist` (si el dueño borra o hace privada la lista, desaparece de Mis Colecciones).

### Navegación
- Gate de autenticación: sin sesión → `AuthScreen`; con sesión → `Scaffold` con `NavigationBar` de **3 pestañas**: **Inicio** (perfil + búsqueda), **Amigos** (red social) y **Mis Colecciones** (listas propias + guardadas + reproductor).

### Enlace URL
- `YouTubeLinkParser`: `extractVideoId` (formato `watch`, `youtu.be`, `/embed/`, `/shorts/`, `/live/`) y `fetchVideoInfo` vía oembed de YouTube (sin API key) para obtener título y canal.

### Comandos útiles
- Build: `./gradlew :app:assembleDebug`
- Tests unitarios: `./gradlew :app:testDebugUnitTest`

### Configuración necesaria
- Para el buscador de YouTube: añadir a `local.properties` (no se sube a git) la línea `YOUTUBE_API_KEY=TU_API_KEY` (YouTube Data API v3).

## Pendiente / ideas
- Reproducción automática de la siguiente canción al terminar.
- Editar listas (título/descripción, toggle público).
- Subir imagen de avatar/banner/photo (Firebase Storage).
- **Reglas de seguridad de Firestore**: revisar y endurecer las reglas (búsqueda de `users`, `friend_requests`, subcolecciones `friends`/`savedCollections`) antes de publicar.
