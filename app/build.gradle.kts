import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Every cloud build gets a higher version number, so the phone accepts it as an update.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "dev.terraformist.someone"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.terraformist.someone"
        minSdk = 34
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    // One fixed signing key, kept in the repo, so each new build installs over the old one.
    signingConfigs {
        create("shared") {
            storeFile = file("someone.keystore")
            storePassword = "someone123"
            keyAlias = "someone"
            keyPassword = "someone123"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Nothing's Glyph Matrix SDK 2.0, from github.com/Nothing-Developer-Programme/GlyphMatrix-Developer-Kit
    implementation(files("libs/glyph-matrix-sdk-2.0.aar"))
}
