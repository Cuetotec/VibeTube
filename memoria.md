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
- `core-splashscreen` 1.2.0 (`androidx.core:core-splashscreen`) para la Splash Screen con retrocompatibilidad.
- `coil-compose` (2.7.0) para imágenes remotas (miniaturas, avatar, banner).
- `androidx-compose-material-icons-core` y `material-icons-extended`.
- `kotlinx-coroutines-play-services` para `tasks.await()`.
- **Eliminada** `androidyoutubeplayer-core` (librería pesada de `pierfrancescosoffritti`): el reproductor ahora usa el `WebView` estándar de Android.
- **Reproducción en segundo plano (Media3 + NewPipeExtractor)**: `media3-exoplayer`, `media3-exoplayer-hls`, `media3-exoplayer-dash`, `media3-session` (1.10.1), `NewPipeExtractor` `v0.26.4` (vía **JitPack**), `okhttp` (5.4.0) y `desugar_jdk_libs` (2.1.4) con `isCoreLibraryDesugaringEnabled = true` (NewPipe usa `java.time`, API 26+, y el minSdk es 24). De `firebase-firestore` se **excluye** `protolite-well-known-types` (duplica `com.google.protobuf.*` con `protobuf-javalite` 4.35.1 y rompe `checkDebugDuplicateClasses`), y a cambio se aportan manualmente los well-known types de googleapis que Firestore necesita (`com.google.type.LatLng` y `com.google.rpc.Status`), generados con `protoc` 35.1 (opción `lite`) en `app/src/main/java/com/google/{type,rpc}`; `protobuf-javalite` 4.35.1 se declara como dependencia explícita (NewPipe solo lo aporta en runtime).

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
  - **Autoplay (siguiente canción)**: el HTML del reproductor captura `onStateChange` de la IFrame API y notifica `VibeTubeBridge.onVideoEnded()` cuando `state == 0` (ENDED); el puente Kotlin (guard `endedHandled`) invoca `playNextTrack()` / `playNextSavedTrack()` del ViewModel, que avanzan a la siguiente canción con **bucle** (`nextTrackId` usa `(index + 1) % tracks.size`, probado en `NextTrackTest`). El cambio de `videoId` en `LaunchedEffect` ejecuta `player.loadVideoById(...) + playVideo()` sobre el WebView único, sin recrearlo. ✅ **Validado de punta a punta en emulador** (Iteración 6).
- **Robustez / anti-crash**:
  - **Reproductor ligero (WebView estándar)**: `player/YouTubePlayerView.kt` carga el embed `https://www.youtube.com/embed/{videoId}?autoplay=1` en un `android.webkit.WebView` único (sin librerías externas). `javaScriptEnabled = true`, `domStorageEnabled` y `mediaPlaybackRequiresUserGesture=false`; `WebViewClient` + `WebChromeClient`; fondo negro.
  - **Origen / postMessage (SOLUCIÓN FINAL: youtube-nocookie.com + host OMITIDO)**: el iframe del embed se comunica con la página padre vía `postMessage`. **Base URL de `loadDataWithBaseURL` y `playerVars.origin` apuntan al dominio oficial de incrustación `https://www.youtube-nocookie.com`**, con `enablejsapi: 1` y `widget_referrer` al mismo dominio. ⚠️ **NO usar `host` en `YT.Player`**: apuntar `host` al mismo origen que la Base URL (`youtube-nocookie.com`) hacía que el iframe del embed quedara en **mismo origen** que la página padre y `www-widgetapi.js` entrara en un **bucle infinito de re-despacho** (`onReady` + `onStateChange=1` a ~145k eventos/s → app al 108% de CPU). Con `host` omitido, el embed se sirve desde `www.youtube.com` (cross-origin), los eventos `postMessage` (`onReady`/`onStateChange`/`onVideoEnded`) llegan igualmente al puente y el iframe no puede invocar `window.VibeTubeBridge` directamente. **Historial del tanteo**: usar `https://www.youtube.com` como base dispara el **error 152-4 (suplantación)**; un dominio propio (`com.cuetotech.vibetube`) hacía que el embed cayera al fallback de `youtube.com` → `postMessage` bloqueado y autoplay roto; `host` = `youtube-nocookie.com` (mismo origen) → flood. La llamada de cambio de canción usa guard `window.player && typeof player.loadVideoById === 'function'` + `loadVideoById(...)` + `playVideo()`.
  - **Deduplicación anti-bucle (doble capa)**: en **JS** (`handleState` con `lastState` + bandera `readySent` + `hasEnded`) y en **Kotlin** (el puente `JsBridge` solo REENVÍA cambios de estado reales y aplica rate-limit de 300ms para `onPlayerStateChange` y 500ms para `onPlayerReady`/`onVideoEnded`). Con esto, aunque el widgetapi re-despache eventos en bucle (p. ej. `3→1→0` al hacer `seekTo` al final), el main thread nunca se satura y el autoplay sigue funcionando.
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
- **Edición de listas propias**: cada tarjeta de lista propia tiene botón de **lápiz (Editar)** que abre `EditPlaylistDialog` con campos **Título**, **Descripción** y **Switch Pública/Privada**. `PlaylistRepository.updatePlaylist(playlistId, title, description, isPublic)` actualiza el documento en Firestore y el listener de `observeUserPlaylists` refresca la UI al instante. El diálogo se aloja en `MainActivity` junto al resto de diálogos compartidos (estado `editingPlaylist` en `PlaylistsViewModel`).
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

## Proceso de desarrollo (historial)

### Iteración 1 — Base del proyecto (commits en `main`)
- Configuración inicial: proyecto Android Kotlin + Compose, tema oscuro, Firebase (Auth + Firestore), `coil-compose` e iconos Material.
- Autenticación con email/contraseña (`AuthScreen`/`AuthViewModel`/`AuthRepository`) con validación y errores mapeados.
- Perfil automático en `users/{uid}` al registrar o iniciar sesión.
- Buscador de YouTube (Data API v3, key vía `BuildConfig.YOUTUBE_API_KEY` desde `local.properties`).
- Playlists en Firestore (`playlists/{id}` con `ownerId`, `isPublic`, `tracks`, `createdAt`) y CRUD desde `PlaylistsViewModel`/`PlaylistsScreen`.
- Eliminación del catálogo de prueba (`songs`, `SongRepository`, `SongViewModel`) → se sustituye por búsqueda real de YouTube.
- Commit: `f38771c` — `feat: add email auth, youtube search, playlists and inline video player`.

### Iteración 2 — Reproductor WebView y estabilidad (commits en `main`)
- Migración del reproductor desde `androidyoutubeplayer-core` (librería pesada) al **WebView estándar** (`player/YouTubePlayerView.kt`): se elimina la dependencia de `libs.versions.toml` y `app/build.gradle.kts`.
- **Crash SIG:9 investigado**: prueba de aislamiento (reproductor sustituido por un `Text`) confirmó que el crash no provenía del player; se blindó el `PlaylistsViewModel` (`observeJob?.cancel()` para no acumular listeners de Firestore) y el detalle usa selección estricta (`selectedSong = selectedTrackId?.let { tracks.find { ... } }`).
- **Errores 152/153 del reproductor resueltos**: causa raíz = usar `https://www.youtube.com` como origen del iframe (mismo origen que YouTube → configuración rechazada). Solución: origen propio `PLAYER_ORIGIN = "https://${BuildConfig.APPLICATION_ID}"` como base URL de `loadDataWithBaseURL` y `playerVars.origin`, `Referer` propio, `strict-origin-when-cross-origin`, `playsinline`, cache desactivada + `clearCache`/`clearHistory`, User-Agent normalizado (sin `; wv`, `Chrome/120`), `mediaPlaybackRequiresUserGesture=false` y overlay de error "Abrir en YouTube" sin reintentos en bucle.
- Confirmación del usuario: la pantalla 'Mis Listas' y el manejo de datos funcionaban correctamente.
- Commit: `f389230` — `fix(player): resolve YouTube WebView playback errors (152/153) and refine error handling`.

### Iteración 3 — Módulo social: 3 pestañas, amigos y colecciones guardadas (commit en `main`)
- **Navegación a 3 pestañas** en `MainActivity`: Inicio | Amigos | Mis Colecciones (antes Inicio | Mis Listas).
- **Modelos nuevos**: `Friend`, `IncomingFriendRequest`, `SavedCollection`, `SavedPlaylist`; `UserProfile` + `photoUrl`.
- **Repositorios nuevos**:
  - `UserProfileRepository.searchUsers` (búsqueda por nombre/email sobre `users`).
  - `FriendshipRepository` (`friend_requests` + subcolección `users/{uid}/friends` con snapshot en ambas direcciones; consultas de un solo campo para evitar índices compuestos; auto-aceptación de solicitud inversa).
  - `SavedCollectionsRepository` (`users/{uid}/savedCollections/{playlistId}`).
  - `PlaylistRepository.observePublicPlaylists` / `observePublicPlaylist` (reusan la observación de `observeUserPlaylists` filtrando `isPublic` en cliente).
- **Pestaña Amigos**: buscador con debounce 350ms y botón Añadir, solicitudes recibidas (Aceptar/Rechazar), lista de amigos que abre el **perfil público** (`FriendProfileScreen`, `viewModel(key = friendUid)`), con listas públicas y botón Guardar/Quitar.
- **Mis Colecciones**: secciones "Mis listas" + "Colecciones guardadas" etiquetadas "Lista de X"; detalle de colección guardada en **solo lectura** (sin quitar canciones ni botón '+' — se añadió `showAddSong` a `YouTubePlayerView` — y con opción de dejar de seguir). Si el dueño borra o hace privada la lista, desaparece automáticamente.
- **Componente compartido** `ui/components/AppSnackbarMessages.kt` para mensajes de estado.
- **Tests**: `YouTubeLinkParserTest` (extracción de IDs de URL en formatos `watch`/`youtu.be`/`embed`/`shorts` + casos negativos) y `NextTrackTest` (selección de la siguiente canción para autoplay).
- Verificación: `./gradlew :app:assembleDebug :app:testDebugUnitTest` en verde al final de cada iteración.
- Commit: `72228e4` — `feat(social): añadir navegación de 3 pestañas, módulo de amigos, perfiles públicos y colecciones guardadas`.

### Iteración 4 — Autoplay y edición de listas
- **Autoplay**: puente JS con `onPlayerStateChange` (estado `0` = vídeo terminado) → `playNextTrack()`/`playNextSavedTrack()` avanzan a la siguiente canción de la lista actual; lógica de siguiente índice extraída a `nextTrackId` (función pura, probada en `NextTrackTest`).
- **Edición de listas**: botón lápiz en las tarjetas de listas propias, `EditPlaylistDialog` (Título, Descripción, Pública/Privada) y `PlaylistRepository.updatePlaylist`; la UI se actualiza al instante vía snapshot listener.
- **Bug de autoplay resuelto (el reproductor se quedaba en ENDED sin avanzar) — refuerzo en `YouTubePlayerView`**:
  1. **Cambio de pista SIN recrear el WebView** (causa raíz según logcat: el reproductor se reiniciaba porque se volvía a disparar `onPlayerReady` al recrear/recargar el WebView con `loadDataWithBaseURL`). El WebView se crea UNA sola vez (`DisposableEffect(Unit)`) y al cambiar la canción en el ViewModel, `LaunchedEffect(videoId)` ejecuta únicamente la función JS nativa `player.loadVideoById(videoId)` vía `evaluateJavascript` (función JS `loadVideo`). Se eliminó el watchdog que recreaba el WebView.
  2. **Cola en JS para el reproductor aún no creado**: si `loadVideo` se invoca antes de que exista el player, el vídeo se guarda en `pendingJsVideoId` y se aplica en cuanto `onYouTubeIframeAPIReady` crea el player (sin recrear el HTML).
  3. **Evento dedicado `onVideoEnded`** en el puente JS (además de `onPlayerStateChange`), deduplicado en JS con la bandera `hasEnded` para no avanzar dos veces del mismo vídeo; `onPlayerReady` confirma que el reproductor JS está listo.
  4. **Sondeo por intervalo eliminado y ENDED estrictamente deduplicado**: un `setInterval` que consultaba `getPlayerState()` disparaba el evento `0` (ENDED) en ráfagas de decenas de llamadas en el mismo milisegundo, saturando el puente Android/JavaScript, bloqueando el ViewModel y congelando el cambio de canción. Se eliminó por completo el sondeo: ahora el avance solo depende del evento nativo `onStateChange`. La bandera `hasEnded` se activa al recibir ENDED (`state=0`) y solo se reinicia cuando cambia el vídeo (`loadVideoById`) o el estado pasa a `PLAYING (1)` / `BUFFERING (3)`.
  - **Logs `Log.d("VibeTubePlayer", ...)`** en cada cambio de estado (valor concreto), en `onVideoEnded`, `onPlayerReady`, en el cambio de `videoId` (`LaunchedEffect`) y en la carga vía `loadVideoById` para depurar en logcat.
- **Bug de reversión al añadir canciones (2 → 3 → 2) resuelto en `PlaylistsViewModel`**: `PlaylistRepository.addTrack` ya era atómico (`FieldValue.arrayUnion` + `await()`). El problema era que un snapshot de Firestore desactualizado podía sobrescribir el estado local. Ahora la canción se añade **de forma optimista** (overlay `pendingTrackAdds` fusionado con `serverPlaylists` en el collector) y el overlay solo se retira cuando el snapshot del servidor confirma la canción (o se revierte con error si la escritura falla). Así un snapshot viejo nunca revierte la lista recién modificada.
- `PlaylistDetail` pasa `onEnded` directamente al reproductor; `YT_PLAYER_STATE_ENDED` sigue como constante pública del player.
- **Solución definitiva (refactor de sincronización y reproductor)**:
  - **Firestore atómico**: `PlaylistRepository.addTrack` escribe SOLO con `FieldValue.arrayUnion(songMap)` sobre `playlists/{playlistId}` + `await()` (nunca lee la lista entera para sobrescribirla con `set()`). El flujo reactivo no emite estado desactualizado mientras se completa la escritura: `observePlaylists` solo publica snapshots confirmados y `PlaylistsViewModel` fusiona el overlay optimista `pendingTrackAdds` hasta que el snapshot del servidor confirma la canción (`confirmPendingTrackAdds`), por lo que un snapshot viejo nunca revierte 3 → 2.
  - **`currentSong` en el ViewModel**: nuevo `StateFlow<Song?>` derivado (`combine` + `stateIn`) de la selección activa (lista propia o guardada) y de las `tracks` que el ViewModel mantiene durante toda la sesión; `playNextTrack()`/`playNextSavedTrack()` avanzan el índice de la canción actual (`currentIndex + 1`) sobre la lista en memoria y actualizan la selección, y `currentSong` se actualiza automáticamente con el objeto de la siguiente canción. **Decisión de fin de lista: bucle (reinicia desde la 0)** para que el autoplay nunca se detenga en pantalla negra (`nextTrackId` usa `(index + 1) % tracks.size`, probado en `NextTrackTest`).
  - **`LaunchedEffect(currentSong?.videoId)` en el reproductor**: observa directamente el ID de la canción actual. Cuando cambia y **`playerReady` es `true`**, ejecuta `webView.evaluateJavascript("player.loadVideoById('$id')", null)` sobre el WebView único (sin recrearlo ni recargar `loadDataWithBaseURL`). Si el reproductor JS aún no está listo, `loadVideo(id)` deja el id en `pendingJsVideoId` (JS) y se aplica en cuanto `onYouTubeIframeAPIReady` crea el player. La dedup `hasEnded` se mantiene (el ENDED solo se reporta una vez por vídeo y se reinicia al cambiar de vídeo o pasar a PLAYING/BUFFERING).
- Verificación: `./gradlew :app:assembleDebug :app:testDebugUnitTest` en verde. Sin commit todavía (pendiente de validar en dispositivo).

### Iteración 5 — Autoplay: causa raíz de orígenes/postMessage (03-08-2026)
- **Diagnóstico con DevTools** del bloqueo del autoplay: el iframe del embed no podía comunicarse con la página padre porque los orígenes no coincidían:
  - Con Base URL/origen propios (`com.cuetotech.vibetube`) → `Failed to execute 'postMessage' ... target origin 'https://www.youtube.com' does not match recipient window's origin ('https://com.cuetotech.vibetube')`: el embed caía al fallback de `youtube.com` y los eventos de estado quedaban bloqueados.
  - Cambiar Base URL + `origin` a `https://www.youtube.com` eliminaba el error de `postMessage`, pero disparaba el **error 152-4 (suplantación)**.
- **SOLUCIÓN FINAL — dominio oficial de incrustación `youtube-nocookie.com`** (todo en `player/YouTubePlayerView.kt`):
  - `PLAYER_ORIGIN = "https://www.youtube-nocookie.com"` como **Base URL de `loadDataWithBaseURL`** y **`playerVars.origin`**.
  - `playerVars` con `enablejsapi: 1` y `widget_referrer` al mismo dominio (además de `autoplay: 1`, `rel: 0`, `playsinline: 1`).
  - **`host: 'https://www.youtube-nocookie.com'` en `YT.Player`** (último bloqueo): `www-widgetapi.js` envía los eventos `postMessage` por defecto a `www.youtube.com`, que no coincide con el origen del WebView; con `host` el embed se sirve y postea desde `youtube-nocookie.com`.
  - Cambio de canción con guard robusto: `if (window.player && typeof player.loadVideoById === 'function') { player.loadVideoById('$id'); player.playVideo(); }`.
- **Validación en DevTools (parcial)**: `player.loadVideoById('dQw4w9WgXcQ')` desde la consola cambia de vídeo **sin errores de origen ni bloqueos 152**. La API Web funciona.
- **Estado**: el autoplay completo (ENDED → siguiente canción) **sigue sin funcionar en el emulador/dispositivo** y queda pendiente de retomar; el puente Kotlin/JS y el avance por índice con bucle ya están implementados.
- Build/tests en verde (`./gradlew :app:assembleDebug :app:testDebugUnitTest`).

### Iteración 6 — Autoplay RESUELTO: bug de sombreado recursivo en el puente + anti-flood (04-08-2026)
- **Síntoma**: en el emulador el autoplay no avanzaba y el widgetapi entraba en floods infinitos de eventos del puente (`onPlayerReady` + `onPlayerStateChange=1` a ~145k eventos/s, y ciclos `3→1→0` al llegar al final del vídeo), con la app al 103% de CPU.
- **Diagnóstico con sondas JS→logcat** (`VibeTubeBridge.probe`, escritura directa sin pasar por el main thread) y contadores muestreados en el puente:
  - El vídeo **sí reproducía** (state=1, `infoDelivery` con `currentTime` avanzando a ritmo normal), pero `window.VibeTubeBridge.onPlayerReady()` se invocaba cientos de miles de veces **sin pasar por los handlers JS del reproductor** (las sondas `ONREADY#`/`HANDLE-STATE#` jamás aparecían). La dedup JS (`readySent`) no frenaba el flood.
  - El `host: youtube-nocookie.com` (mismo origen) causaba el bucle de `onReady`; con `host` omitido (embed en `www.youtube.com`, cross-origin) el flood de `onReady` desaparecía, pero al llegar al final del vídeo el widgetapi volvía a ciclar `3→1→0`.
- **CAUSA RAÍZ del autoplay roto — bug de sombreado en Kotlin**: los cuatro métodos `@JavascriptInterface` del puente (`onPlayerReady`, `onPlayerStateChange`, `onVideoEnded`, `onPlayerError`) tenían **el mismo nombre que los parámetros-lambda del constructor** (`private val onPlayerReady: () -> Unit`, etc.). Dentro de `fun onVideoEnded()`, la llamada `onVideoEnded()` resolvía al **MÉTODO (recursión)** y no a la lambda, por lo que **ningún callback del constructor llegaba jamás al ViewModel**: `playerReady` nunca se activaba, `endedHandled` nunca se reiniciaba por PLAYING/BUFFERING y, sobre todo, `onEnded()` (→ `playNextTrack`) **nunca se invocaba**. El autoplay estaba muerto por completo desde la arquitectura del puente.
- **Solución**:
  1. **Renombrar los parámetros del constructor** de `JsBridge` (`errorHandler`, `stateChangeHandler`, `endedHandler`, `readyHandler`) para romper el sombreado con los nombres de los métodos `@JavascriptInterface`.
  2. **Anti-flood en el puente (Kotlin)**: dedup de estado (solo reenvía cambios reales) + rate-limit (`onPlayerStateChange` ≥300ms, `onPlayerReady`/`onVideoEnded` ≥500ms). El main thread nunca se satura aunque el JS dispare en ráfagas.
  3. **Anti-flood en JS**: dedup `lastState` + `readySent` (onReady una sola vez) + `hasEnded` (ENDED una vez por vídeo), ya presentes.
  4. **`host` OMITIDO en `YT.Player`** (ver más arriba): embed en `www.youtube.com` cross-origin para evitar el bucle de `onReady` por mismo origen.
- **Validación de punta a punta en el emulador** (lista "Francis", hook temporal de prueba que hacía `seekTo(duration-8)` para acelerar el final):
  ```
  onPlayerReady: reproductor JS listo → onPlayerReady (videoId=5zLnaNY58j8)
  Estado del reproductor: 1 (PLAYING) → TEST-SEEK → Estado 3 → 1 → 0 (ENDED)
  onVideoEnded: el vídeo terminó, avanzando a la siguiente canción
  playNextTrack: índice actual=0 (canción=5zLnaNY58j8) -> siguiente=rqbHxGBmFeE de 3 canciones
  Video actual cambiado a: rqbHxGBmFeE → loadVideoInPlayer (player.loadVideoById + playVideo)
  Estado del reproductor: 1 (siguiente canción reproduciéndose)
  ```
  Sin flood (`READY-COUNT 1`, `STATE-COUNT 1`), CPU en 0-30%.
- **Aprendizaje de diagnóstico**: `adb logcat -d | grep INFO:CONSOLE` captura el `console.log` de páginas `loadDataWithBaseURL`, pero bajo un flood de decenas de miles de líneas el buffer lo sobrescribe; por eso las sondas del puente deben escribirse **directo a logcat** (sin `mainHandler.post`) y muestrear cada N llamadas.
- Build/tests en verde: `./gradlew :app:assembleDebug :app:testDebugUnitTest`.

### Iteración 7 — Modo de reproducción: shuffle, repetir lista y repetir canción (04-08-2026)
- **Estado en el ViewModel** (`PlaylistsViewModel`): nuevo `enum RepeatMode { OFF, ALL, ONE }`, y `StateFlow`s observables `isShuffleEnabled`, `repeatMode` y `playbackTick`. Con el aleatorio activado se mantiene un **orden permutado de pistas** (`shuffleOrder`, con la canción actual en primera posición) que garantiza no repetir canciones hasta agotar la lista; se reconstruye al abrir/selectar una lista o al activar el shuffle (`rebuildShuffleOrder`).
- **Lógica de avance** (`nextTrack`, función pura en lugar de `nextTrackId`): combina `RepeatMode` + `isShuffleEnabled`.
  - `RepeatMode.ONE`: mantiene la canción actual; el reproductor la reinicia.
  - Shuffle activo: avanza por el orden permutado (sin repeticiones).
  - `RepeatMode.OFF`: en la última canción **no avanza más** (fin de lista).
  - `RepeatMode.ALL`: en la última canción vuelve al inicio (0 o inicio del orden aleatorio).
- **Reinicio de la canción actual (ONE)**: `playNextTrack`/`playNextSavedTrack` no cambian el `videoId` (el `StateFlow` no re-emite), así que se incrementa un token `playbackTick`; `YouTubePlayerView` lo observa y vuelve a ejecutar `loadVideoById + playVideo` sobre el mismo vídeo.
- **UI** (`PlaylistsScreen`): `PlayerModeControls` bajo el reproductor con botón **Shuffle** (color primario cuando está activo) y botón **Repeat** que cicla OFF → ALL → ONE (icono `Repeat`/`RepeatOne`, color primario cuando activo; content descriptions en `strings.xml`). Aplica tanto a listas propias como guardadas.
- **Ajuste UX (reproducción inicial)**: al abrir o seleccionar una lista **ya no se selecciona ni reproduce la primera canción** — `openPlaylist`/`openSavedPlaylist` dejan `currentSong == null` y el reproductor no se compone (nada de `loadVideoById` al cargar datos). La reproducción arranca solo con acción explícita: **tocar una canción** la selecciona y reproduce, o pulsar el **FAB de Play** (visible cuando la lista tiene canciones pero nada se está reproduciendo) que inicia con la primera canción (o una al azar si el shuffle está activado). Nuevos `startPlayback`/`startSavedPlayback` en el ViewModel y reestructuración de `PlaylistDetail` (la lista de pistas siempre visible; se eliminaron las cadenas `playlist_select_video_*`).
- **Tests**: `NextTrackTest` ampliado a 10 casos (secuencial OFF/ALL, ONE, shuffle con y sin orden, fin de lista, lista vacía/desconocida, canción única) — todos en verde.
- Verificación: `./gradlew :app:assembleDebug :app:testDebugUnitTest` en verde (16 tests, 0 fallos).

### Iteración 8 — Añadir canciones por enlace con varios enlaces a la vez (04-08-2026)
- **Diálogo multilínea** (`UrlDialog` en `MainActivity`): el campo "Por enlace" ahora es un `OutlinedTextField` multilínea (`singleLine = false`, `maxLines = 6`) con placeholder "Pega uno o varios enlaces de YouTube (uno por línea o separados por coma)". El diálogo incluye además un **selector de lista de destino** (radio buttons con las listas propias del usuario) y el botón "Añadir" se habilita solo con texto y lista seleccionada.
- **Extracción de IDs** (`YouTubeLinkParser.extractVideoIds`): nueva función helper que soporta enlaces `watch`/`embed`/`shorts`/`live`/`youtu.be` e IDs limpios de 11 caracteres; limpia espacios, omite líneas vacías y elimina duplicados. `extractVideoId` (strict, un solo enlace) se mantiene intacto.
- **Añadido masivo** (`PlaylistRepository.addMultipleTracks` + `PlaylistsViewModel.addMultipleTracksByUrls(urlsText, playlistId)`): extrae y valida los IDs, consulta metadatos vía oEmbed, descarta canciones ya presentes en la lista, aplica el overlay optimista `pendingTrackAdds` (evita el rollback por snapshot) e inserta todo de forma **agrupada en una sola escritura atómica** (`FieldValue.arrayUnion` con todos los mapas). Confirma con Toast: "Se ha añadido 1 canción a la lista" / "Se han añadido N canciones a la lista".
- **Tests**: `YouTubeLinkParserTest` ampliado a 9 casos (extracción múltiple mixta, dedup de duplicados + líneas vacías, IDs limpios, texto sin enlaces). Total 20 tests en verde.
- Verificación: `./gradlew :app:assembleDebug :app:testDebugUnitTest` en verde.

### Iteración 9 — Subir avatar y portada del perfil (Firebase Storage) (04-08-2026)
- **Dependencias**: se añade `firebase-storage` (sin versión, gestionado por el BOM 34.16.0) en `gradle/libs.versions.toml` y `app/build.gradle.kts`.
- **Nuevo `ProfileStorageRepository`**: sube las imágenes a Firebase Storage bajo `profileImages/{uid}/avatar.jpg` y `profileImages/{uid}/banner.jpg` (una carpeta por usuario para poder restringir el acceso por reglas de seguridad) y devuelve la URL de descarga (`putFile().await()` + `downloadUrl().await()`).
- **`HomeViewModel`**: nuevos StateFlows `isUploadingAvatar`, `isUploadingBanner` y `uploadError` (evento one-shot). `uploadAvatar(uri)`/`uploadBanner(uri)` suben el fichero, guardan la URL en el perfil vía `UserProfileRepository.saveUserProfile` (`avatarUrl` **y** `photoUrl` para que el avatar se propague a amigos/perfiles públicos) y actualizan el estado local de forma optimista. Los fallos se notifican por Toast y `clearUploadError()` limpia el evento.
- **UI (`HomeScreen`)**: la portada y el avatar son **tocables** para cambiarlos. Usa el **photo picker** `ActivityResultContracts.GetContent()` (sin permisos de lectura en runtime) que abre la galería; `ImageTarget` (Avatar/Banner) recuerda qué imagen se está seleccionando. Durante la subida se muestra un `CircularProgressIndicator` y se deshabilita el toque; sobre la portada aparece un icono de cámara. Content descriptions: "Cambiar portada"/"Cambiar avatar".
- **Strings**: nuevos `profile_banner_change`, `profile_avatar_change` y `profile_image_upload_error`.
- Verificación: `./gradlew :app:assembleDebug :app:testDebugUnitTest` en verde (20 tests, 0 fallos).

### Iteración 10 — Confirmación antes de eliminar un vídeo de la lista (04-08-2026)
- **Estado local** (`PlaylistDetail`): nuevo `videoToDelete` (`remember { mutableStateOf<String?>(null) }`) que guarda el ID de la canción pendiente de confirmar. Al pulsar el icono de eliminar de una canción ya **no se borra directamente**: solo se guarda el ID en `videoToDelete`.
- **Diálogo de confirmación**: cuando `videoToDelete != null` se muestra un `AlertDialog` con título "¿Eliminar vídeo?", mensaje "¿Estás seguro de que quieres eliminar este vídeo de la lista?", botón **Eliminar** (texto en `MaterialTheme.colorScheme.error`, estilo destructivo) que ejecuta `onRemoveTrack(videoToDelete)` y resetea el estado a null, y botón **Cancelar** que solo cierra el diálogo reseteando el estado. `onDismissRequest` (toque fuera / Back) también resetea a null.
- **Strings**: nuevos `playlist_delete_track_title`, `playlist_delete_track_message` y `playlist_delete_track_confirm`; reutiliza `common_cancel`.
- Verificación: `./gradlew :app:assembleDebug :app:testDebugUnitTest` en verde (20 tests, 0 fallos).

### Iteración 11 — Confirmación antes de eliminar una lista completa (04-08-2026)
- **Estado local** (`PlaylistsScreen`, nivel raíz): nuevo `playlistToDelete` (`remember { mutableStateOf<Playlist?>(null) }`). Al pulsar el icono de eliminar de una lista ya **no se borra directamente**: solo se guarda la lista (buscándola por su id en `uiState.playlists`) en `playlistToDelete`.
- **Diálogo de confirmación**: cuando `playlistToDelete != null` se muestra un `AlertDialog` con título "¿Eliminar lista?", mensaje "¿Estás seguro de que quieres eliminar esta lista? Esta acción no se puede deshacer.", botón **Eliminar** (texto en `MaterialTheme.colorScheme.error`) que ejecuta `viewModel.deletePlaylist(id)` y cierra el diálogo reseteando el estado, y botón **Cancelar** que solo lo cierra. `onDismissRequest` (toque fuera / Back) también resetea a null.
- **Strings**: nuevos `playlist_delete_confirm_title`, `playlist_delete_confirm_message` y `playlist_delete_confirm_action`; reutiliza `common_cancel`.
- Verificación: `./gradlew :app:assembleDebug :app:testDebugUnitTest` en verde (20 tests, 0 fallos).

### Iteración 12 — Infraestructura de Splash Screen personalizada (Android 12+ y retrocompatibilidad) (04-08-2026)
- **Dependencia**: se añade `androidx.core:core-splashscreen` 1.2.0 en `gradle/libs.versions.toml` y `app/build.gradle.kts`.
- **Tema `Theme.App.Starting`** en `res/values/themes.xml` y `res/values-night/themes.xml` (nuevo): hereda de `Theme.SplashScreen` y define `windowSplashScreenBackground` (`@color/vibe_background`, el mismo fondo del tema principal para una transición continua), `windowSplashScreenAnimatedIcon` (**icono temporal**: `@mipmap/ic_launcher`, el launcher por defecto, hasta tener el nuevo) y `postSplashScreenTheme` (`@style/Theme.VibeTube`). En modo noche usa el mismo fondo porque la app es oscura de forma fija.
- **Manifest**: la `MainActivity` ahora usa `android:theme="@style/Theme.App.Starting"` como tema de arranque.
- **`MainActivity.onCreate`**: se llama a `installSplashScreen()` (import de `androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen`) **antes de `super.onCreate()`**; en Android 12+ se apoya en la API del sistema y en versiones anteriores la emula con `Theme.SplashScreen`.
- Verificación: `./gradlew :app:assembleDebug :app:testDebugUnitTest` en verde (20 tests, 0 fallos). Por ahora la splash muestra el icono por defecto de Android.

### Iteración 13 — Icono de la app y splash con estética oscura y roja (04-08-2026)
- **Icono adaptativo (Android 12+)**: `res/drawable/ic_launcher_background.xml` ahora es un fondo **negro `#0F0F0F`** limpio (se eliminaron las líneas de rejilla del template) y `ic_launcher_foreground.xml` pinta el robot Android en **rojo primario `#FF0033`** (se conserva la sombra sutil del diseño original). `mipmap-anydpi-v26/ic_launcher.xml` e `ic_launcher_round.xml` siguen referenciando estos drawables; el `monochrome` reutiliza el foreground para los iconos temáticos de Android 13+.
- **Iconos legacy (API < 26, minSdk 24)**: se regeneraron `ic_launcher.webp` y `ic_launcher_round.webp` en las 5 densidades (`mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}`) manteniendo la composición del template original — **cuadrado con esquinas redondeadas** (radio ~15%) y **círculo** respectivamente, con el robot rojo `#FF0033` sobre fondo negro `#0F0F0F` (robot a ~80% del ancho en el cuadrado y ~94% en el redondo, igual que el template). Generados vía SVG (`legacy_square.svg`/`legacy_round.svg`) → `rsvg-convert` → `magick` (webp).
- **Splash Screen**: se mantiene la infraestructura de la Iteración 12 (`core-splashscreen` 1.2.0, `Theme.App.Starting` en `values/` y `values-night/`, `@style/Theme.App.Starting` en la `MainActivity`, `installSplashScreen()` en `onCreate`). El `windowSplashScreenAnimatedIcon` (`@mipmap/ic_launcher`) y el fondo (`@color/vibe_background` = `#0F0F0F`) ahora muestran la nueva iconografía oscura/roja de forma coherente.
- Verificación: `./gradlew :app:assembleDebug :app:testDebugUnitTest` en verde (20 tests, 0 fallos).

### Iteración 14 — Logo personalizado de VibeTube (ondas de sonido + play), sin el androide (04-08-2026)
- **`ic_launcher_foreground.xml`**: se **sustituye el robot de Android** por el logo minimalista de VibeTube en rojo `#FF0033`: **dos ondas de sonido** (arcos simétricos que parten del centro superior hacia los laterales) dibujadas como **trazos** (`strokeColor`/`strokeWidth=6`/`strokeLineCap=round`, no relleno, para que se vean como líneas) y un **botón de reproducción** (triángulo relleno) centrado bajo las ondas.
- **Correcciones sobre el XML facilitado**: el namespace `xmlns:android="http://schemas.android.com/apk/apk/xml/android"` era inválido (no compilaba) → se usó el oficial `http://schemas.android.com/apk/res/android`; las ondas como contorno abierto relleno no se verían → se convirtieron a paths con trazo; los extremos originales (x=19 y x=89) salían de la zona segura de los iconos adaptativos (que se recorta a un círculo/máscara) → se trajeron a `x=27..81`.
- **Iconos adaptativos**: `mipmap-anydpi-v26/ic_launcher.xml` e `ic_launcher_round.xml` siguen apuntando a `@drawable/ic_launcher_foreground` (también como `monochrome`), por lo que **todos usan el nuevo logo automáticamente**.
- **Iconos legacy (API < 26)**: regenerados `ic_launcher.webp`/`ic_launcher_round.webp` en las 5 densidades con el mismo logo (cuadrado redondeado ~80% y círculo ~94% de ancho), para que en ningún sitio quede la figura del androide.
- Verificación: `./gradlew :app:assembleDebug :app:testDebugUnitTest` en verde (20 tests, 0 fallos). Render verificado por píxeles (ondas y play en rojo sobre negro).

### Iteración 15 — Fix subida de foto de perfil: orden estricto subida → URL (04-08-2026)
- **Síntoma**: al actualizar el avatar/portada desde la galería la app lanzaba **"Object does not exist at desired location"** — se consultaba Firebase Storage antes de que el objeto subido existiera.
- **`ProfileStorageRepository`**: la subida y la URL de descarga ahora están **encadenadas explícitamente**: `reference.putFile(localUri).continueWithTask { task -> if (!task.isSuccessful) throw ... ; reference.downloadUrl }`. La URL solo se obtiene como continuación de la tarea de subida ya completada (`.await()` sobre el `Task<Uri>` final); si `putFile` falla, la excepción se propaga y **nunca** se consulta `downloadUrl`. El Uri local (`content://...`) recibido de la galería se pasa tal cual a `putFile`; la ruta destino sigue siendo `profileImages/{uid}/avatar.jpg` / `banner.jpg` (coherente con las reglas de Storage ya configuradas).
- **Bloqueo de navegación durante la subida** (`HomeScreen`): mientras `isUploadingAvatar || isUploadingBanner`, se muestra un **overlay a pantalla completa** (fondo semitransparente + `CircularProgressIndicator` + texto "Subiendo imagen…", nuevo string `profile_uploading`) que impide al usuario navegar fuera antes de que termine la subida (el permiso del Uri local de la galería solo es válido en la sesión actual).
- Verificación: `./gradlew :app:assembleDebug :app:testDebugUnitTest` en verde (20 tests, 0 fallos).

### Iteración 16 — Unificación de la subida de imágenes de perfil (avatar y fondo) (04-08-2026)
- El error **"Object does not exist at desired location"** seguía apareciendo tanto al cambiar el avatar como al seleccionar una imagen de fondo local, así que la lógica de subida se unifica en una única función genérica del repositorio.
- **`ProfileStorageRepository.uploadImageToStorage(localUri: Uri, storagePath: String): String`** (nuevo, único punto de subida):
  1. `storageRef.putFile(localUri).await()` — sube PRIMERO el archivo local (`content://...`) y espera a que termine.
  2. `return storageRef.downloadUrl.await().toString()` — obtiene la URL de descarga SOLO cuando la subida ha terminado (si `putFile` falla, `await` lanza la excepción y nunca se consulta `downloadUrl`).
- **Rutas de Storage por usuario (carpetas del propio perfil, a la altura del bucket)**:
  - Avatar → `avatars/{uid}.jpg`.
  - Fondo/portada → `backgrounds/{uid}.jpg`.
  - Wrappers `uploadAvatar(uid, uri)` y `uploadBanner(uid, uri)` que delegan en la función genérica con esas rutas.
- **Persistencia en Firestore**:
  - Avatar → URL guardada en `avatarUrl` y **`photoUrl`** (este último se propaga a amigos/perfiles públicos).
  - Fondo → URL guardada en `bannerUrl` (el campo correspondiente del perfil).
- **Gestión de estados en la UI** (ya existente desde la Iteración 15): `isUploadingAvatar`/`isUploadingBanner` muestran un **overlay bloqueante a pantalla completa** ("Subiendo imagen…") + spinner en el elemento concreto; los elementos de avatar/fondo quedan no pulsables mientras se sube, evitando re-taps y que se muestre una imagen previa errónea.
- Verificación: `./gradlew :app:assembleDebug :app:testDebugUnitTest` en verde (20 tests, 0 fallos).
- **Nota para las reglas de Storage**: ahora las imágenes viven en `avatars/{uid}.jpg` y `backgrounds/{uid}.jpg` (no en `profileImages/{uid}/...`). Las reglas de seguridad deben cubrir esas rutas nuevas, p. ej.: `match /avatars/{uid}.jpg { allow read: if request.auth != null; allow write: if request.auth.uid == uid }` (idéntico para `/backgrounds/`).

### Iteración 17 — Sin Firebase Storage: imágenes de perfil en almacenamiento interno (04-08-2026)
- **Decisión**: se abandona Firebase Storage por completo. Avatar y fondo se guardan **localmente en la memoria interna del dispositivo** (Internal Storage, `context.filesDir`). Se eliminan todas las llamadas y dependencias hacia `Firebase.storage` para evitar errores de red.
- **`ProfileMediaRepository` (nuevo, sustituye a `ProfileStorageRepository`)**, con `Context`:
  - `copyImageToPrivateStorage(source: Uri, fileName: String): String` — copia la imagen seleccionada de la galería (`content://...`) a `filesDir` (en `Dispatchers.IO`) y devuelve la **ruta local `file:///...`**. Si no se puede abrir el origen, lanza `IOException`.
  - `deleteLocalImage(fileUrl)` — borra la imagen local previa si está dentro de `filesDir`, evitando archivos huérfanos al reemplazar avatar/fondo.
- **`HomeViewModel`** pasa a ser `AndroidViewModel(application)` (para obtener `filesDir` sin DI manual); el default `viewModel()` sigue funcionando.
  - `uploadAvatar(uri)` → copia a `filesDir/avatar_{uid}.jpg`; guarda la ruta en **`avatarUrl` + `photoUrl`**.
  - `uploadBanner(uri)` → copia a `filesDir/banner_{uid}.jpg`; guarda la ruta en **`bannerUrl`**.
  - La ruta local se persiste en Firestore (campo del perfil) y se usa para cargar la imagen en la UI (Coil soporta `file:///...` en `AsyncImage`).
- **Dependencias**: eliminados `implementation(libs.firebase.storage)` de `app/build.gradle.kts` y el acceso `firebase-storage` de `gradle/libs.versions.toml`. Se conservan `firebase-auth` y `firebase-firestore`. Sin cambios en `HomeScreen` (Coil ya carga rutas `file:///`).
- Verificación: `./gradlew :app:assembleDebug :app:testDebugUnitTest` en verde (20 tests, 0 fallos).
- **Nota**: al ser almacenamiento local, avatar/fondo solo se ven en el mismo dispositivo; `photoUrl` con `file:///...` no se renderiza en otros dispositivos (amigos/perfiles públicos). Las imágenes previas subidas a Storage siguen visibles en los perfiles que las referencian.

### Iteración 18 — El reproductor ya no se reinicia al girar el móvil (04-08-2026)
- **Síntoma**: al girar el dispositivo (cambio de orientación), la actividad se recreaba y el vídeo volvía a empezar desde el principio.
- **Causa**: el reproductor usa un `WebView` con la API de YouTube (iframe), no ExoPlayer. Por defecto, Android destruye la actividad (y con ella el `WebView` y el estado `remember { PlayerRef() }`) ante un cambio de configuración como la rotación.
- **Solución en `AndroidManifest.xml`**: en `.MainActivity` (única actividad del proyecto y donde se reproduce el vídeo) se añade
  `android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout"`.
  Con esto Android **no destruye la actividad** al girar: el `WebView` sobrevive y la reproducción continúa en el segundo exacto en el que estaba (posición + estado playing/paused preservados de forma implícita por el propio reproductor). Compose sigue adaptando el layout al girar vía `LocalConfiguration`, sin recrear la pantalla.
- Nota: no hace falta `rememberSaveable`/ViewModel para retener la posición porque el reproductor (WebView) no se destruye; si más adelante se quisiera soportar recreaciones forzadas (muerte del proceso, cambio de tema oscuro), habría que guardar `currentTime` y rehacer `seekTo` vía la API de YouTube.
- Verificación: `./gradlew :app:assembleDebug :app:testDebugUnitTest` en verde (20 tests, 0 fallos).

### Iteración 19 — Fix crash al iniciar sesión tras los cambios de Avatar/Fondo (04-08-2026)
- **Síntoma**: la app se cerraba inesperadamente al iniciar sesión después de los cambios de avatar y fondo (almacenamiento local).
- **Refactor de `HomeViewModel` (AndroidViewModel → ViewModel + factory)**: el constructor ya no recibe `application` como único parámetro con valor por defecto, por lo que `AndroidViewModelFactory` no podía instanciarlo (Kotlin solo genera el constructor reducido público cuando TODOS los parámetros tienen valor por defecto). Ahora es un `ViewModel` con `companion object { fun factory(application): ViewModelProvider.Factory }` (`viewModelFactory { initializer { ... } }`) que construye `ProfileMediaRepository(application)` y se pasa en `HomeScreen` vía `viewModel(factory = HomeViewModel.factory(LocalContext.current.applicationContext as Application))`.
- **Revisión de modelos (nulabilidad)**: todas las propiedades de imagen son opcionales en todos los modelos — `UserProfile.avatarUrl/bannerUrl/photoUrl`, `Friend.photoUrl`, `IncomingFriendRequest.fromPhotoUrl`, `SavedCollection.ownerPhotoUrl`, `SavedPlaylist.ownerPhotoUrl` (`String? = null`). Los repositorios las leen con `as? String` (null-safe) y escriben con `?: ""`. La UI ya tiene fallbacks (`isNullOrBlank()` → iniciales). Sin cambios necesarios.
- **Carga defensiva del perfil** (`HomeViewModel.profileFlow`): la lectura del perfil en Firestore se envuelve en `runCatching`; si falla (permisos de Firestore, red o campos de imagen inválidos), se continúa con un **perfil por defecto sin imágenes** (nombre/email de la sesión), y la escritura de ese perfil por defecto también es tolerante a fallos. Así el inicio de sesión **completa siempre el flujo hacia la pantalla principal**, mostrando la imagen por defecto (iniciales), en lugar de quedarse en estado de error o lanzar una excepción no capturada.
- **`AuthViewModel.submit`**: además de `runCatching` (ya presente), ahora `isLoading` se resetea en éxito para no dejar el botón bloqueado al completar el login.
- **Verificación de dependencias/Firebase**: la inicialización está intacta. En el manifest fusionado siguen presentes `com.google.firebase.provider.FirebaseInitProvider` (auto-init de `FirebaseApp`), `FirebaseAuthRegistrar` y `FirestoreRegistrar`. No existía ni se eliminó ningún `FirebaseApp.initializeApp` explícito (el auto-init lo aporta firebase-common vía google-services). Solo se eliminó la dependencia `firebase-storage`, que no participa en auth ni firestore.
- Verificación: `./gradlew :app:assembleDebug :app:testDebugUnitTest` en verde (20 tests, 0 fallos).

### Iteración 21 — Reproducción en segundo plano (pantalla apagada) con Media3 + NewPipeExtractor (05-08-2026)
- **Objetivo**: poder seguir escuchando la música con la pantalla apagada y con los controles del sistema (centro de control / bloqueo) y la notificación multimedia.
- **Enfoque (decisión del usuario)**: el `WebView` actual sigue siendo el reproductor de vídeo en pantalla; la reproducción en segundo plano la hace un **servicio Media3** con el **audio real** de YouTube, obtenido con **NewPipeExtractor** (se integra la librería para extraer la URL de audio; no se usa la API key de YouTube). El vídeo se queda **silenciado (mute)** mientras el servicio reproduce el audio (evita doble sonido).
- **`player/PlaybackService.kt`** (nuevo): `MediaSessionService` de Media3. Crea un `ExoPlayer` (`DefaultMediaSourceFactory` con `DefaultHttpDataSource.Factory` con User-Agent estilo Chrome y redirects cross-protocol habilitados) y un `MediaSession`. `onGetSession` devuelve la sesión; `onDestroy` libera el player.
- **`data/YouTubeStreamResolver.kt`** (nuevo): `NewPipeDownloader` (implementa `Downloader` de NewPipe con OkHttp, `ConnectionSpec.RESTRICTED_TLS`, cabeceras de NewPipe y 429 → `ReCaptchaException`). `YouTubeStreamResolver.resolveAudioUrl(videoId)` inicializa NewPipe (`Localization.fromLocale(Locale.getDefault())` + `ContentCountry("US")`), obtiene el extractor del stream y **prefiere audio puro** (m4a/webma/opus) por mayor `averageBitrate`; si no hay, cae al stream de vídeo progresivo de menor altura. Devuelve `null` si falla (la app sigue sonando vía WebView).
- **`player/PlaybackController.kt`** (nuevo): conecta el `MediaController` al servicio vía `SessionToken(context, ComponentName(...))` (en Media3 1.10.1 `PlaybackServiceToken` ya no existe). `syncPlaylist(tracks, startIndex, repeatMode)` resuelve las URLs de audio con **concurrencia limitada (4, semáforo) y timeout de 20 s por pista**, construye `MediaItem` con metadatos (título, artista, portada `https://i.ytimg.com/vi/{id}/hqdefault.jpg`) y las envía con `setMediaItems(items, serviceStart, 0L)`. Las pistas que no resuelven su audio se omiten y `vmToServiceIndex` mapea índice ViewModel → índice servicio (para `seekTo`). `seekTo(vmIndex, play)`, `setRepeatMode`, `stop`, `release` (libera la future del `MediaController`). Expone `isActive: StateFlow<Boolean>`.
- **`PlaylistsViewModel`**: pasa de `ViewModel` a **`AndroidViewModel(application)`** para poder crear el `PlaybackController` con el contexto (el default `viewModel()` sigue funcionando porque el constructor solo recibe `Application`). Añade `backgroundAudioActive` (StateFlow), `syncedPlaylistKey` (evita re-sincronizar la misma lista en cada selección) y `syncBackgroundPlayback()` que: calcula las pistas activas (`orderedActiveTracks()`, respetando shuffle), compara la clave de sesión, y hace `syncPlaylist` completo o `seekTo` si ya está sincronizada. Se llama en `startPlayback`, `startSavedPlayback`, `selectTrack`, `selectSavedTrack`, `toggleShuffle`, `cycleRepeatMode`, `playNextTrack` y `playNextSavedTrack`. `onCleared` → `playbackController.release()`.
- **`player/YouTubePlayerView.kt`**: nuevo parámetro `muted`. Si está activo, el `buildPlayerHtml` inyecta `player.mute()` y un `LaunchedEffect(muted)` ejecuta `mute()`/`unMute()` vía `evaluateJavascript` al cambiar, para silenciar el WebView mientras suena el servicio Media3.
- **`PlaylistsScreen.kt`**: recoge `backgroundAudioActive` del ViewModel y lo pasa al `PlaylistDetail` (y este al `YouTubePlayerView`).
- **`AndroidManifest.xml`**: permisos `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `WAKE_LOCK`, `POST_NOTIFICATIONS`, `ACCESS_NETWORK_STATE`; servicio `com.cuetotech.vibetube.player.PlaybackService` con `android:foregroundServiceType="mediaPlayback"`, `exported="true"` e intent-filter `androidx.media3.session.MediaSessionService`.
- **`MainActivity`**: en Android 13+ se solicita el permiso `POST_NOTIFICATIONS` en tiempo de ejecución (necesario para que se muestre la notificación multimedia).
- **`app/build.gradle.kts` / `gradle/libs.versions.toml` / `settings.gradle.kts`**: dependencias de Media3/NewPipeExtractor/OkHttp/desugaring, `isCoreLibraryDesugaringEnabled = true` y repositorio **JitPack** (`https://jitpack.io`) en `dependencyResolutionManagement`.
- Verificación: `./gradlew :app:assembleDebug` en verde. Pendiente de probar en móvil real (pantalla apagada + controles del sistema).

### Iteración 22 — Fix crash al iniciar sesión por `NoClassDefFoundError: com.google.type.LatLng` (05-08-2026)
- **Síntoma**: al pulsar "Iniciar sesión" con credenciales válidas la app se cerraba en el momento de componer la pantalla principal (reproducido en emulador con cuenta real).
- **Causa raíz**: la exclusión de `protolite-well-known-types` de la Iteración 21 era errónea ("protobuf-javalite es superconjunto"): `protobuf-javalite` (4.35.1 y anteriores) **NO incluye** los well-known types de googleapis. Firestore necesita `com.google.type.LatLng` y `com.google.rpc.Status`, que solo aporta `protolite`. Al excluirlo, la primera consulta (`PlaylistRepository.observeUserPlaylists` → `whereEqualTo`, lanzada justo tras el login) construye un `Value` cuyo schema referencia `LatLng` → `NoClassDefFoundError: Failed resolution of: Lcom/google/type/LatLng;`. Reintroducir `protolite` rompe el build por `checkDebugDuplicateClasses` (duplica `com.google.protobuf.*` con `protobuf-javalite`).
- **Solución**: se mantiene la exclusión de `protolite` y se **generan a mano los dos well-known types de googleapis** con `protoc` 35.1 (mismo nº que `protobuf-javalite` 4.35.1) y opción `lite`, commiteados como fuente en `app/src/main/java/com/google/type/LatLng.java` (más `LatLngOrBuilder`/`LatLngProto`) y `app/src/main/java/com/google/rpc/Status.java` (más `StatusOrBuilder`/`StatusProto`). Además `protobuf-javalite` 4.35.1 se declara como dependencia explícita porque NewPipe solo lo aporta en runtime y el `javac` de la app necesita el runtime en el classpath de compilación.
- **Regeneración** (si se sube `protobuf-javalite`): `protoc --proto_path=. --java_out=lite:out google/type/latlng.proto google/rpc/status.proto` (protos de `googleapis/googleapis`).
- Verificación: `./gradlew :app:assembleDebug :app:testDebugUnitTest` en verde; login validado en emulador (API 37) con cuenta real → pantalla principal estable, 0 crashes.

### Iteración 23 — El audio se corta al bloquear la pantalla: wake mode, foco de audio y guard de ciclo de vida (05-08-2026)
- **Síntoma**: con permisos de notificación concedidos y la batería en "Sin restricciones", al bloquear la pantalla el audio se detiene inmediatamente.
- **`PlaybackService.kt` (causa principal)**: el `ExoPlayer` se creaba sin `setWakeMode`, sin atributos de audio explícitos y sin `setHandleAudioBecomingNoisy`. Al apagar la pantalla el dispositivo puede suspender la CPU/red → el streaming se corta → el player se detiene. Se añade:
  - `player.setWakeMode(C.WAKE_MODE_NETWORK)` → wakelock parcial + lock de red mientras reproduce (mantiene CPU y red despiertas con la pantalla apagada).
  - `player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), handleAudioFocus = true)` → foco de audio de una app de música.
  - `player.setHandleAudioBecomingNoisy(true)` → pausa al desconectar auriculares/altavoz externo.
- **`YouTubePlayerView.kt` (guard de ciclo de vida)**: se añade un `LifecycleEventObserver` (vía `LocalLifecycleOwner`) que en **`ON_PAUSE`/`ON_STOP` NO pausa ni detiene el reproductor WebView** (el audio real en segundo plano lo reproduce el servicio Media3; pausarlo aquí cortaría la música) y en **`ON_RESUME`** reanuda el vídeo (`player.playVideo()`) solo si el audio lo gestiona la app (no está mute). Se confirma además que no existía ningún `ON_PAUSE`/`ON_STOP`/`LifecycleEventObserver` previo que llamara a `pause()`/`stop()` del reproductor (grep del proyecto), y que `MainActivity`/`PlaylistsScreen` no pausan nada al pausar la Activity.
- **`AndroidManifest.xml` (ya correcto, sin cambios)**: `FOREGROUND_SERVICE_MEDIA_PLAYBACK` y `FOREGROUND_SERVICE` presentes; servicio `.player.PlaybackService` con `android:foregroundServiceType="mediaPlayback"`, `exported="true"` e intent-filter `androidx.media3.session.MediaSessionService` (además `android:stopWithTask="false"` para conservar la reproducción al cerrar la app desde recientes).
- **Nota**: la extracción de audio de NewPipeExtractor falla en el emulador (IP de datacenter → "Could not get ytInitialData"); en IP residencial (móvil real) suele funcionar. Si al bloquear NO aparece la notificación multimedia con controles, el servicio no está activo y el audio saldría solo del WebView (que no puede sonar en segundo plano) — a vigilar en móvil real.
- Verificación: `./gradlew :app:assembleDebug :app:testDebugUnitTest` en verde.

### Iteración 24 — Instrumentación de la extracción de audio y arranque explícito del servicio (05-08-2026)
- **Síntoma en móvil real**: mismo corte al apagar la pantalla y **sin notificación multimedia** → el `PlaybackService` nunca llega a activarse y el audio sale solo del WebView (no puede sonar con la pantalla apagada). La extracción NewPipe fallaba además en el emulador (IP de datacenter).
- **`YouTubeStreamResolver.kt` (logs de la extracción)**: `Log.d` de request/response HTTP en `NewPipeDownloader` (incluido código de respuesta), `Log.w` para HTTP 429 → `ReCaptchaException`, `Log.d` al inicializar NewPipe, `Log.d` por `videoId` con nº de streams de audio candidatos y formato/bitrate elegido, `Log.w` cuando no hay audio puro y se cae al fallback de vídeo, y `catch` separados para `ReCaptchaException`, `ParsingException` y `Exception` genérica (los errores de red `IOException` y el 429 ya se logueaban; se añade el detalle). Nuevos imports: `org.schabi.newpipe.extractor.exceptions.ParsingException`.
- **`PlaybackController.kt` (conexión al servicio)**: `controller()` loguea "Conectando MediaController...", "MediaController conectado..." y "No se pudo conectar...". `awaitController` loguea la excepción real del future si la conexión falla. Nuevo `suspend fun ensureConnected(): Boolean` que fuerza `controller()` (arranca `PlaybackService` vía `MediaController.Builder` con `SessionToken`) y devuelve si quedó conectado. `syncPlaylist` loguea "X/Y pistas con audio resuelto", "ninguna pista pudo resolver su audio" y el set/pista de inicio, y devuelve `false` si no se pudo reproducir nada.
- **`PlaylistsViewModel.kt` (arranque explícito)**: en `syncBackgroundPlayback()`, antes de resolver/seek, si el servicio no está activo se llama `playbackController.ensureConnected()` → el `PlaybackService` arranca **en cuanto el usuario pulsa reproducir** (antes de la extracción). Si `syncPlaylist` devuelve `false` (no se resolvió ningún audio) se muestra un Toast: "No se pudo extraer el audio de YouTube: se reproduce solo dentro de la app, sin segundo plano." (nuevo string `playback_audio_extraction_failed`).
- **Diagnóstico pendiente en móvil real**: capturar `adb logcat` con los tags `VibeTubeStream` (resolución de audio) y `VibeTubePlayback`/`VibeTubePlayer` (conexión y reproducción). Si NewPipe falla por consentimiento de la UE o IP residencial, se verá el motivo exacto (ParsingException / ReCaptchaException / IOException).
- Verificación: `./gradlew :app:assembleDebug :app:testDebugUnitTest` en verde.

### Iteración 25 — Fix del bucle de redirecciones del consentimiento de YouTube (SOCS) y extracción paralela (05-08-2026)
- **Síntoma (logcat del móvil real)**: `java.net.ProtocolException: Too many follow-up requests: 21` → `syncPlaylist: 0/7 pistas con audio resuelto` → "ninguna pista pudo resolver su audio". La causa es el bucle de redirecciones de consent.youtube.com (`ucbcb=1`): sin cookie jar, YouTube no recuerda el consentimiento y redirige a la página de consentimiento en cada llamada; OkHttp aborta tras 20 saltos y toda la extracción falla.
- **`NewPipeDownloader.kt` (nuevo, extraído de `YouTubeStreamResolver.kt`)**: downloader propio con OkHttpClient que ahora lleva un **`InMemoryCookieJar`** sembrado con la **cookie `SOCS=CAESEwgDEgk2ODE3ODk1OTAaAmVuIAEaBgiA_L2yBg`** (dominio `.youtube.com`, path `/`, Secure, expira en 1 año). El jar guarda en memoria las cookies que devuelve YouTube (`YSC`, `VISITOR_INFO1_LIVE`, `GPS`, …) deduplicadas por nombre+dominio+path y las reenvía en cada request (`Cookie.matches`). Con SOCS + jar el consentimiento de la UE se resuelve una sola vez y desaparece el bucle de 21 redirecciones.
- **`YouTubeStreamResolver.kt` (extracción en paralelo)**: `resolveAudioUrl` → renombrada a **`resolveSingleAudioUrl(videoId): String?`** (misma lógica, añadido `catch (CancellationException) { throw }` para no tragar la cancelación del withContext). Nuevo **`resolveAudioUrls(videoIds): List<String?>`** que lanza todas las extracciones en paralelo con `Dispatchers.IO` + `async`/`awaitAll`, un semáforo de 4 concurrentes y un `withTimeoutOrNull(20_000)` por canción, con try-catch por canción (re-lanzando `CancellationException`) para que el fallo de una no rompa la lista. Devuelve lista alineada por índice.
- **`PlaybackController.kt` (optimización de `syncPlaylist`)**: ahora delega en `YouTubeStreamResolver.resolveAudioUrls(tracks.map { it.youtubeId })` y elimina su semáforo local y su `resolveAudioUrls` privada (imports limpios). Si la pista inicial no resolvió audio, el fallback explícito `serviceStart = vmToServiceIndex[startIndex] ?: 0` arranca desde la **primera pista válida** de la lista (en orden), con `Log.w` avisando de qué pista se usa en su lugar.
- Verificación: `./gradlew :app:assembleDebug :app:testDebugUnitTest` en verde. Pendiente: probar en móvil real y confirmar en logcat que desaparece el `ProtocolException` y que las pistas se resuelven (`VibeTubeStream`).
- **Autoverificación (revisión senior) de la Iteración 25**:
  - **Null-safety**: verificado por bytecode del jar `NewPipeExtractor-v0.26.4` que `getAudioStreams()`/`getVideoStreams()` devuelven un `ArrayList` no-null (posiblemente vacío) y que `averageBitrate` es `int` primitivo. `maxByOrNull`/`minByOrNull` + elvis manejan listas vacías; `content` se devuelve como `String?` sin dereferencia. **No hay NPE** en el flujo (el único caso teórico sería un servicio no-YouTube devolviendo null, y siempre se usa `ServiceList.YouTube`).
  - **Corrutinas — se corrigieron 2 fallos reales en `PlaybackController.kt`**: (1) `awaitController` no liberaba el `ListenableFuture` si la corrutina se cancelaba (la conexión quedaba huérfana) ni tenía timeout → ahora `withTimeoutOrNull(CONNECT_TIMEOUT_MS=15s)` + `continuation.invokeOnCancellation { MediaController.releaseFuture(future) }`. (2) Para que ese release sea seguro con varios awaiters compartiendo el mismo future, `controller()` queda serializado con un `Mutex` (`connectMutex.withLock`) y, si la conexión falla/tima out, se resetea `connectFuture = null` para permitir reintentos. Los `CancellationException` de `resolveSingleAudioUrl`/`resolveAudioUrls` ya se re-lanzaban correctamente (sin pérdida); el semáforo de 4 + `withTimeoutOrNull(20s)` garantiza tiempo acotado (sin deadlock).
  - **Memoria/instancias**: `YouTubeStreamResolver` es `object` (singleton); `NewPipeDownloader`+`OkHttpClient`+`CookieJar` se crean UNA sola vez en `ensureInitialized()` (`@Synchronized` + flag `@Volatile`); los extractores por request son transitorios y GC-collectables; `PlaybackController` es una instancia por `PlaylistsViewModel` y se libera en `onCleared()`. Sin fugas.
  - Verificación: `./gradlew :app:assembleDebug :app:testDebugUnitTest` en verde tras los fixes.

### Iteración 26 — Causa raíz del fallo de extracción (versión de cliente WEB) y fix de la race de mute (05-08-2026)
- **Síntomas**: en el móvil real seguía sin reproducirse con pantalla apagada y sin notificación multimedia; en el emulador TODAS las pistas fallaban con `Could not get ytInitialData` aunque el consentimiento ya estaba resuelto (visitor_id → 200).
- **Investigación de poToken (descartado)**: se evaluó portar el `PoTokenProvider` WebView de la app NewPipe (BotGuard). Por bytecode de `NewPipeExtractor-v0.26.4` se confirmó que es un **no-op en esta versión**: `YoutubeStreamExtractor.onFetchPage` solo consulta `getAndroidClientPoToken` y `getIosClientPoToken` (NUNCA `getWebClientPoToken`/`getWebEmbedClientPoToken`); los tokens Android/iOS exigen DroidGuard de Google Play (inviable); y cuando el provider devuelve `null` para Android, `fetchAndroidClient` usa `getAndroidReelPlayerResponse` (cliente ANDROID **Reel/Shorts**, que no requiere poToken). Es decir, el extractor ya cae a un flujo sin poToken; implementar el provider no cambiaría nada en v0.26.4.
- **Causa raíz del `Could not get ytInitialData`**: el mensaje se lanza en `YoutubeParsingHelper.getInitialData()` (regex de `ytInitialData` en un HTML), invocado desde `getClientVersion()` cuando esta intenta extraer la **versión del cliente WEB** desde `https://www.youtube.com/sw.js_data` y, si falla, desde la página de búsqueda `https://www.youtube.com/results?search_query=&ucbcb=1`. `getClientVersion()` se llama desde `prepareDesktopJsonBuilder` (paso `next` de `onFetchPage`, SIN try-catch) → en IPs de datacenter esas páginas devuelven una página robot sin `ytInitialData` → ParsingException → toda la extracción falla. Nota: el mismo `?ucbcb=1` era el URL del bucle de consentimiento (`Too many follow-up requests: 21`) del móvil real en builds sin cookie SOCS.
- **`YouTubeStreamResolver.kt` (preselección de la versión del cliente)**: nuevo `presetWebClientVersion()` llamado en `ensureInitialized()`: por reflexión pone el campo estático privado `YoutubeParsingHelper.clientVersion` a `2.20260120.01.00` (el mismo valor de fallback hardcodeado del propio extractor) si está vacío. Al estar poblado, `getClientVersion()` devuelve el valor en memoria y **desaparecen las peticiones a www.youtube.com** del flujo de extracción (que pasa a ser solo innerTube `youtubei.googleapis.com`, funcional incluso en IPs de datacenter). Si la reflexión falla (minificación/cambio de campo) → `Log.w` y se ignora (seguiría el flujo normal).
- **`YouTubePlayerView.kt` (fix race de mute)**: el `LaunchedEffect(muted)` tenía el guard `playerReady`; si `isActive` pasaba a `true` (servicio conectado) antes de `onPlayerReady`, el mute se saltaba y el WebView sonaba por su cuenta mientras el servicio también reproducía (audio doble). Ahora `PlayerRef.muted` guarda el último estado solicitado, `LaunchedEffect` lo registra siempre (sin guard) y `onPlayerReady` reaplica el mute pendiente (log "Aplicando mute pendiente en onPlayerReady").
- **Verificación en emulador (API 37, IP de datacenter)**: al reproducir la lista "Francis": `POST youtubei.googleapis.com/youtubei/v1/reel/reel_item_watch → 200`, `POST www.youtube.com/youtubei/v1/next → 200`, `fetchPage OK` en **7/7** y `syncPlaylist: 7/7 pistas con audio resuelto` → `7/7 sincronizadas, inicio en índice 0`. Sesión de Media3 `active=true`, `state=PLAYING(3)`; **notificación multimedia visible** (`MediaStyle`, `category=transport`, acciones Rewind/Pause/Forward, flags `FOREGROUND_SERVICE`). Con la **pantalla apagada** (keyevent POWER, `mWakefulness=Asleep`): la sesión sigue `PLAYING` y la posición avanza (36 s → 60 s) y la notificación persiste. ✅ El flujo completo funciona: extracción → servicio → notificación → reproducción con pantalla apagada.
- **Conclusión para el móvil real**: el `Too many follow-up requests: 21` original era el consentimiento (`?ucbcb=1`), ya resuelto con la cookie SOCS (Iteración 25); con la versión de cliente preseleccionada la extracción ya no depende del HTML de www.youtube.com. En IP residencial debería funcionar; si algo fallara, capturar `adb logcat` con los tags `VibeTubeStream`/`VibeTubePlayback`.
- Verificación: `./gradlew :app:assembleDebug :app:testDebugUnitTest` en verde.

### Iteración 27 — Handoff transparente entre WebView (primer plano) y PlaybackService (segundo plano) (05-08-2026)
- **Problema**: al silenciar el WebView para evitar el "audio doble" con el servicio Media3, la app sonaba perfectamente con la pantalla apagada (ExoPlayer) pero NO tenía sonido con la pantalla encendida (WebView siempre en silencio).
- **Objetivo (especificación del usuario)**: sistema de conmutación (handoff) basado en el estado de la pantalla o de la app: en primer plano suena el WebView (con el servicio pausado); en segundo plano (pantalla apagada o app en background) suena el ExoPlayer retomando DESDE la posición exacta del WebView; al volver, el WebView retoma desde la posición del ExoPlayer.
- **`player/WebPlayerControl.kt` (nuevo)**: `interface WebPlayerControl` con `play()`, `pause()`, `setMuted()`, `seekTo(positionMs)` y `currentPosition(onResult: (Long?) -> Unit)` (el callback siempre se invoca; `null` si no se puede leer). `class WebPlayerControlHandle(onAttached, onDetached)` es el punto de enganche compartido: la vista registra su control interno mientras está compuesta y se des-registra al destruirse, notificando al handoff con los callbacks.
- **`player/PlaybackHandoff.kt` (nuevo)**: orquesta las transiciones en el scope del ViewModel:
  - Estado de primer plano = **pantalla encendida && app en primer plano**, combinando un `BroadcastReceiver` dinámico (`ACTION_SCREEN_ON`/`ACTION_SCREEN_OFF`, registrado con `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)` y sembrado con `PowerManager.isInteractive`) y el `ProcessLifecycleOwner` (`lifecycle-process`).
  - `handOffToService()` (segundo plano): lee la posición del WebView (JS, con `withTimeoutOrNull(2s)` + `suspendCancellableCoroutine` y guard `continuation.isActive` para la doble reanudación timeout/callback) → pausa y silencia el WebView → `playbackController.playFromPosition(pos)` (seek + play en el ExoPlayer).
  - `handOffToWebView()` (primer plano): solo si el WebView está registrado (si no, el servicio sigue siendo la fuente) → lee `currentPosition` del ExoPlayer → `pause()` del servicio → des-silencia, `seekTo(pos)` y `play()` en el WebView.
  - `onWebPlayerAttached()` (al componerse el WebView con sesión activa en primer plano) sincroniza con el servicio; `onWebPlayerDetached()` (al abandonar la pantalla) reanuda el servicio para que la música no se corte.
  - Observador de `playbackController.isActive`: si el servicio se activa/reconecta en primer plano, se pausa de inmediato (evita audio doble).
  - `onPlaybackSynced()`: política de audio tras sincronizar/saltar de pista (pausar el servicio en primer plano, reproducir en segundo plano).
- **`player/PlaybackController.kt`**: nuevos `play()`, `pause()`, `currentPosition(): Long?` y `playFromPosition(positionMs)` (seek dentro de la pista actual + play). `syncPlaylist` gana `startPlaying: Boolean = true` que, si es false, deja el servicio **preparado y pausado** (sin pedir foco de audio) — clave para no arrebatarle el foco al WebView en primer plano.
- **`player/YouTubePlayerView.kt`**: funciones JS nuevas (`playVideo()`, `pauseVideo()`, `getCurrentTime()` → segundos o -1, `seekToPosition(seconds)` con allowSeekAhead); nuevo parámetro `webPlayerControl: WebPlayerControlHandle?` con la implementación interna (`viewControl` vía `remember`, encolada en el hilo del WebView con `post`) registrada en un `DisposableEffect`; **fix del guard de mute** (usaba `typeof player.mute()` con paréntesis → siempre "undefined" y el mute no se aplicaba; ahora `typeof player.mute === 'function'`), centralizado en `evaluateMute()`.
- **`PlaylistsViewModel.kt`**: crea el `PlaybackHandoff` (con `viewModelScope`), expone `isForeground` y `webPlayerControl`, llama a `start()` en el init y `stop()` en `onCleared`; en `syncBackgroundPlayback` calcula `startPlaying = !isForeground` y lo pasa a `syncPlaylist`/`seekTo`, y llama a `onPlaybackSynced()` tras cada sincronización.
- **`PlaylistsScreen.kt`**: `muted = backgroundAudioActive && !isForeground`; pasa `isForeground` y `webPlayerControl` a `PlaylistDetail` → `YouTubePlayerView`.
- **Verificación en emulador (API 37)**: canción seleccionada → WebView `state=1` (sonido en primer plano) y sesión `PAUSED(2)` en posición 0 (servicio preparado sin foco). `KEYCODE_POWER` → `handOffToService` → sesión `PLAYING(3)` en `position=33971` (posición capturada del WebView) y WebView mute+pause. `KEYCODE_POWER` de nuevo → `handOffToWebView` → sesión `PAUSED(2)` en `position=46835` y WebView `unMute` + `state=3→1` (reanuda donde estaba el servicio). `KEYCODE_HOME` → `handOffToService` (sesión `PLAYING` en 60.9s); volver a la app → `handOffToWebView` (sesión `PAUSED` en 67.9s). ✅ Continuidad de posición exacta en ambas direcciones y cero audio doble.
- **Dependencia añadida**: `androidx.lifecycle:lifecycle-process` (para `ProcessLifecycleOwner`).
- Verificación: `./gradlew :app:assembleDebug :app:testDebugUnitTest` en verde (20 tests, 0 fallos).

### Iteración 20 — Blindaje del login frente a crashes (04-08-2026)
- **Síntoma**: SIGKILL (Signal 9) en el hilo principal justo al iniciar sesión. Nota: SIGKILL lo emite el sistema (lmkd por presión de memoria, ANR o fuerza de cierre), no es una excepción Java; el blindaje evita crashes por excepciones no capturadas y rutas de imagen inválidas.
- **Referencias de Firebase Storage**: grep exhaustivo del proyecto → **cero** referencias a `FirebaseStorage`/`Firebase.storage`/`firebase-storage` en código, Gradle o manifiesto. Ningún `FirebaseStorage.getInstance()` pendiente. Tampoco hay `killProcess`/`System.exit`/`exitProcess`.
- **Flujo de login protegido**:
  - `AuthViewModel.submit`: `runCatching` sustituido por **try-catch explícito** con `Log.e("LOGIN_ERROR", "Error en login", e)` (re-lanzando `CancellationException`), conservando el mapeo de errores de Firebase y el reset de `isLoading`.
  - `MainActivity.onCreate`: la navegación inicial (`setContent { MainScreen() }`) se envuelve en **try-catch** con `Log.e("LOGIN_ERROR", "Error en navegación inicial", e)`; si algo falla en la composición, se muestra `StartupErrorFallback()` (pantalla de error con nuevo string `startup_error_message`) en lugar de cerrar la app. Ojo: Compose no permite try-catch alrededor de invocaciones de composables, por eso el try-catch va alrededor de `setContent(...)` (ámbito no-composable de `onCreate`).
- **Imágenes nulas o con ruta inválida → valor por defecto**: nuevo `ProfileMediaRepository.existingLocalImage(url)`: URLs remotas (antigua Firebase Storage) se conservan; rutas locales `file://...` cuyo archivo ya no existe en disco (reinstalación, borrado) devuelven `null`. `HomeViewModel.profileFlow` lo aplica a `avatarUrl`, `bannerUrl` y `photoUrl`, de modo que la UI cae a la imagen por defecto (iniciales) sin romper el flujo.
- **Manifest**: verificado — la única activity es `.MainActivity` (existe, sin renombrar), temas `Theme.App.Starting`/`Theme.VibeTube` presentes, sin componentes eliminados.
- Verificación: `./gradlew :app:assembleDebug :app:testDebugUnitTest` en verde (20 tests, 0 fallos).

## Pendiente / ideas
- ~~Reproducción automática de la siguiente canción al terminar.~~ **Hecho y validado** (Iteración 6): `onVideoEnded` → `playNextTrack`/`playNextSavedTrack` → `loadVideoById` → siguiente canción, sin flood y sin saturar el main thread.
- ~~Editar listas (título/descripción, toggle público).~~ **Hecho**.
- ~~Modo de reproducción secuencial o aleatoria (shuffle).~~ **Hecho** (Iteración 7): selector en el reproductor de las listas con Shuffle (ON/OFF) y Repeat que cicla OFF → ALL → ONE.
- ~~Botón "Por enlace" con varios enlaces a la vez.~~ **Hecho** (Iteración 8): diálogo multilínea + selector de lista de destino + `extractVideoIds` + `addMultipleTracksByUrls` con escritura agrupada y Toast de confirmación.
- ~~Subir imagen de avatar/banner/photo (Firebase Storage).~~ **Hecho** (Iteración 9): `ProfileStorageRepository` (carpeta `profileImages/{uid}`), `HomeViewModel.uploadAvatar`/`uploadBanner` y portada/avatar tocables con photo picker y progreso de subida. Pendiente: definir **reglas de seguridad de Storage** (acceso al propietario).
- **Reglas de seguridad de Firestore**: revisar y endurecer las reglas (búsqueda de `users`, `friend_requests`, subcolecciones `friends`/`savedCollections`) antes de publicar.
- ~~Reglas de seguridad de Firebase Storage.~~ **Hecho**: limitar `profileImages/{uid}/...` a su propietario (auth.uid == uid).
