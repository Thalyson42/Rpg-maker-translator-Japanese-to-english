plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// O número da versão sobe a cada build do GitHub Actions; assim cada APK novo é uma atualização válida.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.rpgtl"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rpgtl.translator"
        minSdk = 24
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.1.$buildNumber"
    }

    // Chave de assinatura FIXA: todo APK gerado usa a mesma assinatura, então o Android
    // instala por cima da versão anterior (atualização) sem precisar desinstalar.
    signingConfigs {
        create("rpgtl") {
            storeFile = file("rpgtl.jks")
            storePassword = "rpgtlsign"
            keyAlias = "rpgtl"
            keyPassword = "rpgtlsign"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("rpgtl")
        }
        debug { signingConfig = signingConfigs.getByName("rpgtl") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    implementation("com.google.mlkit:translate:17.0.3")
}
