import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 内部版本号：主版本 × 10000 + 次版本 × 100 + 修订版本。
val appVersionName = "1.2.0"
val baseVersion = appVersionName.substringBefore("-")
val isPreRelease = appVersionName != baseVersion
val appVersionParts = baseVersion.split('.').map(String::toInt)
require(appVersionParts.size == 3 && appVersionParts.all { it >= 0 } && appVersionParts.drop(1).all { it < 100 })

var appVersionCode = appVersionParts[0] * 10000 + appVersionParts[1] * 100 + appVersionParts[2]
if (isPreRelease) {
    appVersionCode -= 1
}

android {
    namespace = "io.github.escosis.hwtfaceswap"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "io.github.escosis.hwtfaceswap"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
    }

    // 调试构建使用 Android Studio 的默认签名；发布签名可通过环境变量提供。
    val releaseStore = providers.environmentVariable("WRISTMIRROR_KEYSTORE").orNull
    if (!releaseStore.isNullOrBlank()) {
        signingConfigs.create("owner") {
            storeFile = file(releaseStore)
            storePassword = providers.environmentVariable("WRISTMIRROR_STORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("WRISTMIRROR_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("WRISTMIRROR_KEY_PASSWORD").get()
        }
    }
    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
            isDebuggable = false
            if (!releaseStore.isNullOrBlank()) signingConfig = signingConfigs.getByName("owner")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    implementation(libs.miuix)
    implementation(libs.miuix.preference)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.androidx.navigationevent.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
