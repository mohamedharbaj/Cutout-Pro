import org.gradle.api.tasks.util.PatternFilterable

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.cutoutpro.kznpqe"
    // 26+ lets us ship only the adaptive (vector) launcher icon: no PNG icons for old densities.
    minSdk = 26
    targetSdk = 36
    versionCode = 2
    versionName = "2.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // Do not remove or modify these signingConfigs. They are necessary for building, installing,
  // and updating Android apps in AI Studio.
  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      // R8 shrinks code; shrinkResources drops unused resources. This is what keeps the APK small.
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  packaging {
    resources {
      // License/metadata files duplicated by dependencies; never read at runtime.
      excludes += listOf(
        "/META-INF/{AL2.0,LGPL2.1}",
        "/META-INF/DEPENDENCIES",
        "/META-INF/LICENSE*",
        "/META-INF/NOTICE*",
        "/DebugProbesKt.bin",
        "/kotlin-tooling-metadata.json"
      )
    }
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// The slim app no longer uses Room / ONNX / DataStore / material-icons-extended, but the old files that
// need them are still in the repo (data/**, ModelManager, OnnxSegmenter, ui/screens/**,
// CutoutProApplication). They are not referenced by MainActivity/CutoutApp, so we keep them out of
// the Kotlin compilation. This is what fixes "Unresolved reference 'room'" and the missing
// icons/strings errors that made :app:compileReleaseKotlin fail.
tasks.configureEach {
  if (name.startsWith("compile") && name.endsWith("Kotlin") && this is PatternFilterable) {
    exclude(
      "com/example/CutoutProApplication.kt",
      "com/example/data/**",
      "com/example/engine/ModelManager.kt",
      "com/example/engine/OnnxSegmenter.kt",
      "com/example/ui/screens/**"
    )
  }
}

// Only what the app really uses. Removed on purpose (size): ONNX Runtime + 224 MB model download,
// material-icons-extended, Room/KSP, OkHttp, Coil, DataStore, Navigation, Firebase/Google services.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.core.ktx)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  // Free on-device AI subject segmentation (runs via Google Play services: tiny APK impact).
  implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")

  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
}
