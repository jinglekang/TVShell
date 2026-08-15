plugins {
  alias(libs.plugins.android.application)
}

android {
  namespace = "com.example.tvshell"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.example.tvshell"
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "1.0.0"
  }

  splits {
    abi {
      isEnable = true
      reset()
      include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
      isUniversalApk = false
    }
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/release.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = System.getenv("KEY_ALIAS")
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
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  kotlin {
    compilerOptions {
      jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
  }
  packaging {
    jniLibs {
      useLegacyPackaging = true
    }
  }
  bundle {
    language {
      enableSplit = false
    }
  }

  lint {
    disable += "NotificationPermission"
    disable += "Aligned16KB"
    disable += "OldTargetApi"
    disable += "GradleDependency"
    disable += "NewerVersionAvailable"
    disable += "AndroidGradlePluginVersion"
    disable += "DiscouragedApi"
  }
}

dependencies {
  implementation(libs.geckoview)
  implementation(libs.zxing.core)
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.core.ktx)
}
