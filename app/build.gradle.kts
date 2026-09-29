import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.krishna.remindly"
    compileSdk = 34

    defaultConfig {
        // v2.02 (N33): Maps SDK key comes from local.properties (MAPS_API_KEY=...) — never committed.
        // GitHub Actions supplies it as the MAPS_API_KEY environment variable (a repository secret).
        // Empty ⇒ the app runs the map picker on OSM until a build carries the key.
        manifestPlaceholders["MAPS_API_KEY"] = run {
            val f = rootProject.file("local.properties")
            val props = Properties()
            if (f.exists()) FileInputStream(f).use { props.load(it) }
            props.getProperty("MAPS_API_KEY")?.takeIf { it.isNotBlank() }
                ?: System.getenv("MAPS_API_KEY").orEmpty()
        }
        applicationId = "com.krishna.remindly"
        minSdk = 26
        targetSdk = 34
        versionCode = 2011000
        versionName = "2.11"
    }

    // Signing material is NEVER in the repository. It comes from keystore.properties (git-ignored)
    // or from REMINDLY_* environment variables; with neither present the build is unsigned.
    val ksProps = Properties().also { p ->
        val f = rootProject.file("keystore.properties")
        if (f.exists()) FileInputStream(f).use { p.load(it) }
    }
    fun ks(name: String): String? = ksProps.getProperty(name) ?: System.getenv("REMINDLY_" + name.uppercase())
    val ksFile = ks("storeFile")?.let { rootProject.file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (ksFile != null) create("shared") {
            storeFile = ksFile
            storePassword = ks("storePassword")
            keyAlias = ks("keyAlias") ?: "remindly"
            keyPassword = ks("keyPassword")
        }
    }

    buildTypes {
        debug {
            if (ksFile != null) signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            if (ksFile != null) signingConfig = signingConfigs.getByName("shared")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test:core:1.5.0")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")   // v2.9 (N45): branded launch splash
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")   // v2.02: Task.await() for the GPS fix
    implementation("com.google.android.gms:play-services-location:21.3.0")
    // v2.02 (N33): Google Maps SDK (map picker) + Compose bindings; OSM (osmdroid) stays as fallback.
    implementation("com.google.android.gms:play-services-maps:18.2.0")
    implementation("com.google.maps.android:maps-compose:4.4.1")
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.osmdroid:osmdroid-android:6.1.18")
}
