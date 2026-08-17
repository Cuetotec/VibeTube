# 🎵 VibeTube

**VibeTube** es una aplicación nativa de Android diseñada para la reproducción fluida y optimizada de vídeos e historias de audio de YouTube. Combina lo mejor de dos mundos: una interfaz visual rica basada en WebView/Jetpack Compose para reproducción en primer plano y una canalización nativa de audio de alto rendimiento (**Media3 / ExoPlayer**) para reproducción continua con la pantalla apagada o en segundo plano.

---

## 🚀 Características Principales

- **📱 Reproducción Híbrida Inteligente (Audio & Vídeo):**
  - **Pantalla encendida:** Reproducción de vídeo nativa/web fluida en primer plano con soporte para pantalla completa.
  - **Pantalla apagada / Background:** Transición transparente a reproductor nativo de audio en segundo plano sin interrumpir la reproducción.
- **🔄 Conmutación Dinámica (Seamless Handshake):** Sincronización automática de marcas de tiempo (*timestamps*) entre el WebView y ExoPlayer al encender        o apagar la pantalla.
- **🎨 Personalización de Perfil:**
  - **Avatar & Fondos:** Edición y personalización del avatar de usuario y la imagen de fondo de perfil para darle una identidad única a tu cuenta.
- **⚡ Extracción de Streams en Tiempo Real:** Utiliza **NewPipeExtractor** para resolver streams de audio directa y eficientemente (`M4A`, `Opus`) sin         depender de la API oficial de YouTube.
- **🎛️ Control Multimedia Nativo:** Integración completa con la notificación de control multimedia de Android, pantalla de bloqueo, controles Bluetooth y        de auriculares via `Media3 / MediaSession`.
- **📜 Gestión de Listas de Reproducción:** Reproducción secuencial y concurrente gestionada defensivamente mediante corrutinas de Kotlin.
- **👥 Funcionalidades Sociales y Amigos:**
  - **Añadir Amigos:** Conéctate con otros usuarios para ver qué están escuchando y compartir el catálogo de contenido.
  - **Compartir Listas de Reproducción:** Envía y recibe listas de reproducción directamente entre amigos o genera enlaces compatibles.
- 🚗 **Soporte completo para Android Auto:** Explora tus listas de reproducción y reproduce tu música directamente desde la pantalla táctil de tu coche o       desde los mandos del volante.
- 🔀 **Controles avanzados de reproducción:** Modo aleatorio (Shuffle), repetición, visualización de carátulas en tiempo real y *handoff* transparente          entre pantalla y segundo plano.
    
---

## 🛠️ Arquitectura y Tecnologías Usadas

- **Lenguaje:** Kotlin 100%
- **Diseño de UI:** Jetpack Compose / Material 3
- **Base de Datos:** Firebase
- **Reproducción Nativa:** AndroidX Media3 (`ExoPlayer`, `MediaSession`, `MediaController`)
- **Extracción de Red:** [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)
- **Cliente HTTP:** OkHttpClient con gestión persistente de cookies (`CookieJar`) y soporte para headers de consentimiento (`SOCS`)
- **Asincronía & Concurrencia:** Kotlin Coroutines (`StateFlow`, `SharedFlow`, `Mutex`, `Semaphore`)
- **Gestión de Ciclo de Vida:** LifecycleObserver, BroadcastReceiver de estado de pantalla (`ACTION_SCREEN_OFF` / `ACTION_SCREEN_ON`)
- **Media3 & ExoPlayer:** Reproducción nativa en segundo plano mediante `MediaLibraryService` y `MediaSession`.
- **Integración con Android Auto:** Exposición del árbol de contenidos multimedia (`MediaBrowserServiceCompat`) para la navegación e integración con los       controles físicos del vehículo.
- **Extracción de Audio Nativa:** Utiliza `NewPipeExtractor` con soporte para caché en memoria, timeouts optimizados y manejo de cookies (`SOCS`) para         garantizar cargas instantáneas.

---

## 📱 Capturas de Pantalla

<div align="center">

| Pantalla Principal | Pantalla Amigos | Pantalla Mis Listas |
| :---: | :---: | :---: |
| <img src="https://github.com/user-attachments/assets/bb6d5e52-cac4-4f86-bbdc-f9d74480e6a7" width="220" alt="pantalla principal"/> | <img src="https://github.com/user-attachments/assets/e3fc4026-b7c2-4c2a-b349-a32132018cd6" width="220" alt="pantalla amigos"/> | <img src="https://github.com/user-attachments/assets/c2506fca-acfb-4e7e-9d9f-6d891bdf7958" width="220" alt="pantalla mis listas"/> |

</div>

## 🔒 Aspectos Técnicos Destacados

1. **Gestión Defensiva de Nulos:** Resistencia a fallos en la API de YouTube mediante control estricto de nulos en streams de audio/vídeo y fallbacks automáticos a streams de vídeo de baja resolución con audio.
2. **Sincronización Thread-Safe:** Uso de `Mutex` en `PlaybackController` para evitar "zombie futures" y conexiones duplicadas con el `MediaSession`.
3. **Control de Concurrencia:** Limitación de solicitudes simultáneas a YouTube mediante `Semaphore` para evitar baneos de IP o restricciones por tasa de refresco.
4. **Resistencia a Bloqueos:** Implementación de `PoTokenProvider` y gestión de consentimiento de cookies para pasar las validaciones de Botguard y Proof-of-Origin de YouTube.

---
