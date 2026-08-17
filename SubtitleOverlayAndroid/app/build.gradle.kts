plugins {
    id("com.android.application")
}

val releaseKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
val releaseKeystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
val releaseKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")
val releaseSigningConfigured = listOf(
    releaseKeystorePath,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

val buildVersionCode = System.getenv("ANDROID_VERSION_CODE")?.toIntOrNull() ?: 1000
val buildVersionName = System.getenv("ANDROID_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "0.2.1"

if (System.getenv("REQUIRE_RELEASE_SIGNING") == "true" && !releaseSigningConfigured) {
    throw org.gradle.api.GradleException(
        "Release signing is required, but one or more Android signing environment variables are missing."
    )
}

android {
    namespace = "com.sun.subtitleoverlay"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sun.subtitleoverlay"
        minSdk = 26
        targetSdk = 36
        versionCode = buildVersionCode
        versionName = buildVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.core:core-ktx:1.18.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
