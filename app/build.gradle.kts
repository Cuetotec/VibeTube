import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val youtubeApiKey = localProperties.getProperty("YOUTUBE_API_KEY").orEmpty()

android {
    namespace = "com.cuetotech.vibetube"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.cuetotech.vibetube"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "YOUTUBE_API_KEY", "\"$youtubeApiKey\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // NewPipeExtractor usa java.time (API 26+); con desugaring funciona en
        // minSdk 24.
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(platform(libs.firebase.bom))
    // protolite-well-known-types (transitivo de Firestore) incluye copias de
    // com.google.protobuf.* que DUPLICAN protobuf-javalite 4.35.1 (traído por
    // NewPipeExtractor) y rompe checkDebugDuplicateClasses. Se excluye y, a
    // cambio, se aportan manualmente los well-known types de googleapis que
    // Firestore necesita y que protobuf-javalite NO incluye (com.google.type.
    // LatLng y com.google.rpc.Status), generados con protoc 35.1 (lite) en
    // app/src/main/java/com/google/{type,rpc}.
    implementation(libs.firebase.firestore) {
        exclude(group = "com.google.firebase", module = "protolite-well-known-types")
    }
    implementation(libs.firebase.auth)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.play.services)
    // Reproducción en segundo plano: Media3 (ExoPlayer + MediaSessionService).
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.session)
    // Extracción de la URL de audio real de YouTube (NewPipeExtractor).
    implementation(libs.newpipe.extractor)
    // NewPipe solo aporta protobuf-javalite en runtime; se declara explícito
    // para que las clases com.google.type.LatLng y com.google.rpc.Status
    // (generadas con protoc 35.1/lite) compilen en el classpath de la app.
    implementation(libs.protobuf.javalite)
    implementation(libs.okhttp)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}