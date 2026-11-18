import java.util.Properties
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val signingProps = Properties().also { p -> rootProject.file("keystore/signing.properties").takeIf { it.exists() }?.inputStream()?.use { p.load(it) } }
fun secret(k: String, env: String): String? = System.getenv(env) ?: signingProps.getProperty(k)
android {
    namespace = "com.goga.carlauncher"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.goga.carlauncher"
        minSdk = 26
        targetSdk = 29
        versionCode = 14
        versionName = "1.7.3"
    }
    // Signing secrets are NOT in the repo: keystore/ (jks + signing.properties) stays only on the build machine,
    // or pass CL_STORE_FILE / CL_STORE_PASSWORD / CL_KEY_ALIAS / CL_KEY_PASSWORD as environment variables.
    signingConfigs {
        create("release") {
            storeFile = file(secret("storeFile", "CL_STORE_FILE") ?: "../keystore/carlauncher.jks")
            storePassword = secret("storePassword", "CL_STORE_PASSWORD")
            keyAlias = secret("keyAlias", "CL_KEY_ALIAS") ?: "carlauncher"
            keyPassword = secret("keyPassword", "CL_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        abortOnError = true; checkReleaseBuilds = false
        // Sideloaded head-unit app (not on Google Play): targetSdk 29 is deliberate — it keeps legacy storage
        // access to dashcam recordings on Android 10 FYT units.
        disable += setOf("ExpiredTargetSdkVersion")
    }
    testOptions { unitTests { isIncludeAndroidResources = true; all { it.maxHeapSize = "1536m" } } }
    packaging { resources { excludes += setOf("META-INF/*.kotlin_module", "kotlin/**", "DebugProbesKt.bin") } }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
}
