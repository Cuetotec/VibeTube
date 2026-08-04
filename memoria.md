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

## Pendiente / ideas
- ~~Reproducción automática de la siguiente canción al terminar.~~ **Hecho y validado** (Iteración 6): `onVideoEnded` → `playNextTrack`/`playNextSavedTrack` → `loadVideoById` → siguiente canción, sin flood y sin saturar el main thread.
- ~~Editar listas (título/descripción, toggle público).~~ **Hecho**.
- ~~Modo de reproducción secuencial o aleatoria (shuffle).~~ **Hecho** (Iteración 7): selector en el reproductor de las listas con Shuffle (ON/OFF) y Repeat que cicla OFF → ALL → ONE.
- ~~Botón "Por enlace" con varios enlaces a la vez.~~ **Hecho** (Iteración 8): diálogo multilínea + selector de lista de destino + `extractVideoIds` + `addMultipleTracksByUrls` con escritura agrupada y Toast de confirmación.
- ~~Subir imagen de avatar/banner/photo (Firebase Storage).~~ **Hecho** (Iteración 9): `ProfileStorageRepository` (carpeta `profileImages/{uid}`), `HomeViewModel.uploadAvatar`/`uploadBanner` y portada/avatar tocables con photo picker y progreso de subida. Pendiente: definir **reglas de seguridad de Storage** (acceso al propietario).
- **Reglas de seguridad de Firestore**: revisar y endurecer las reglas (búsqueda de `users`, `friend_requests`, subcolecciones `friends`/`savedCollections`) antes de publicar.
- ~~Reglas de seguridad de Firebase Storage.~~ **Hecho**: limitar `profileImages/{uid}/...` a su propietario (auth.uid == uid).
