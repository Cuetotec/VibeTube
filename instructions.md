# VibeTube
Un reproductor de música estilo Spotify que utiliza enlaces e IDs de YouTube para gestionar el catálogo y crear listas de reproducción, enfocado a usuarios que buscan música gratuita y personalizada de forma legal mediante reproducción incrustada.

## Stack
- Lenguaje: Kotlin (Android Nativo)
- Framework UI: Jetpack Compose (Material Design 3)
- Base de datos & Auth: Firebase Firestore y Firebase Authentication (SDK Android Oficial)
- Reproductor Multimedia: android-youtube-player (de Pierfrancesco Soffritti)
- Concurrencia: Kotlin Coroutines & Flow

## Comandos (Gradle en Terminal Linux)
- `./gradlew :app:installDebug` — Compila la app y la instala en el dispositivo físico conectado por USB.
- `./gradlew test` — Ejecuta los tests unitarios locales en la JVM.
- `./gradlew cC` — Ejecuta los tests conectados (instumentales) en el dispositivo.
- `./gradlew lint` — Revisa el estilo de código y rendimiento de Android.

## Estructura del proyecto (Arquitectura MVVM)
- `app/src/main/java/com/tuusuario/tubeify/data/` — Modelos de datos (Data Classes) y repositorios que conectan con Firebase Firestore.
- `app/src/main/java/com/tuusuario/tubeify/ui/` — Pantallas en Jetpack Compose (Views) y lógica de presentación (ViewModels).
- `app/src/main/java/com/tuusuario/tubeify/player/` — Lógica de gestión y listeners del reproductor incrustado de YouTube.
- `app/src/main/res/` — Recursos del sistema (iconos, strings traducibles, colores oficiales).

## Convenciones
- Código en Kotlin limpio: camelCase para variables/funciones, PascalCase para clases y funciones `@Composable`.
- Los IDs de YouTube deben ser Strings estrictos de 11 caracteres (ej: `dQw4w9WgXcQ`). Nunca almacenes la URL completa en Firestore.
- Manejo de estados: Usa `StateFlow` en los ViewModels para emitir los estados de la interfaz de forma reactiva a Compose.
- Toda petición a Firebase Firestore debe ir dentro de un bloque `try-catch` capturando `FirebaseFirestoreException` y manejando estados de Carga, Éxito o Error.

## No hagas
- NO uses vistas XML tradicionales; todo el diseño debe ser declarativo con Jetpack Compose.
- NO ocultes el reproductor de vídeo de YouTube de la pantalla ni intentes reproducir audio en segundo plano (vulnera los Términos de Servicio de YouTube). El vídeo siempre debe ser visible (mínimo en miniatura).
- NO subas el archivo `google-services.json` de Firebase al repositorio Git. Añádelo al `.gitignore`.
- NO añadas dependencias en el `build.gradle.kts` sin antes consultar el impacto en el rendimiento de Gradle.

## Flujo de trabajo
- Antes de una tarea no trivial (como la sincronización en tiempo real de listas), propón un plan técnico y espera mi OK.
- Una tarea a la vez; al terminar, dime qué archivos modificaste exactamente para que los revise en mi OpenCode.
- Si no estás seguro al 80% de cómo interactúa una función con el ciclo de vida de Compose, pregunta. No inventes APIs obsoletas.

## Documentación
- API Oficial de Firestore para Android: https://firebase.google.com/docs/firestore
- Repositorio del reproductor legal de YouTube: https://github.com/PierfrancescoSoffritti/android-youtube-player