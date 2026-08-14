import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
    id("com.google.dagger.hilt.android")
}

val canonicalReleaseKeystorePropertiesFile = rootProject.file("keystore.properties")
val releaseKeystorePropertiesFile = listOf(
    canonicalReleaseKeystorePropertiesFile,
    rootProject.file("../keystore.properties"),
).firstOrNull { it.isFile } ?: canonicalReleaseKeystorePropertiesFile
val releaseKeystoreProperties = Properties().apply {
    if (releaseKeystorePropertiesFile.isFile) {
        releaseKeystorePropertiesFile.inputStream().use(::load)
    }
}

fun normalizedSigningValue(value: String?): String? =
    value
        ?.trim()
        ?.takeIf(String::isNotEmpty)

fun releaseSigningEnvironmentValue(environmentName: String): String? =
    normalizedSigningValue(providers.environmentVariable(environmentName).orNull)

fun releaseSigningPropertyValue(propertyName: String): String? =
    normalizedSigningValue(releaseKeystoreProperties.getProperty(propertyName))

fun releaseSigningValue(propertyName: String, environmentName: String): String? =
    releaseSigningEnvironmentValue(environmentName)
        ?: releaseSigningPropertyValue(propertyName)

val releaseStoreFileFromEnvironment =
    releaseSigningEnvironmentValue("DESKCUBBY_RELEASE_STORE_FILE")
val releaseStoreFile =
    releaseStoreFileFromEnvironment ?: releaseSigningPropertyValue("storeFile")
val releaseStorePassword = releaseSigningValue("storePassword", "DESKCUBBY_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseSigningValue("keyAlias", "DESKCUBBY_RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseSigningValue("keyPassword", "DESKCUBBY_RELEASE_KEY_PASSWORD")
val releaseSigningValues = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
)
val releaseSigningConfigured = releaseSigningValues.all { !it.isNullOrBlank() }
val releaseSigningPartiallyConfigured = releaseSigningValues.any { !it.isNullOrBlank() } &&
    !releaseSigningConfigured
val releaseTaskRequested = gradle.startParameter.taskNames.any {
    it.contains("release", ignoreCase = true)
}

if (releaseTaskRequested && releaseSigningPartiallyConfigured) {
    throw GradleException(
        "Release signing is incomplete. Configure storeFile, storePassword, keyAlias and " +
            "keyPassword in keystore.properties or the DESKCUBBY_RELEASE_* environment variables.",
    )
}
if (releaseTaskRequested && !releaseSigningConfigured) {
    throw GradleException(
        "Release signing is not configured. Run scripts/generate-release-keystore.ps1 first.",
    )
}

android {
    namespace = "com.deskcubby.app"
    compileSdk = 36
    compileSdkExtension = 19

    defaultConfig {
        applicationId = "com.deskcubby.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 40
        versionName = "0.18.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                val configuredStoreFile = File(checkNotNull(releaseStoreFile))
                val storeFileBaseDirectory = if (releaseStoreFileFromEnvironment != null) {
                    rootProject.projectDir
                } else {
                    releaseKeystorePropertiesFile.parentFile
                }
                storeFile = if (configuredStoreFile.isAbsolute) {
                    configuredStoreFile
                } else {
                    storeFileBaseDirectory.resolve(configuredStoreFile.path)
                }
                storePassword = checkNotNull(releaseStorePassword)
                keyAlias = checkNotNull(releaseKeyAlias)
                keyPassword = checkNotNull(releaseKeyPassword)
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

android.applicationVariants.configureEach {
    outputs.configureEach {
        (this as com.android.build.gradle.api.ApkVariantOutput).outputFileName = "DeskCubby.apk"
    }
}

kapt {
    correctErrorTypes = true
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")

    implementation(project(":plugin-api:core"))
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.8")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.legere:pdfiumandroid:1.0.35")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    // Room 2.8.4's schema bundle serializers are generated against 1.8.1. DataStore's older
    // transitive constraint otherwise mixes 1.7.3 runtime classes into migration tests.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    kapt("androidx.room:room-compiler:2.8.4")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("androidx.webkit:webkit:1.16.0")

    // HttpURLConnection rejects WebDAV's PROPFIND method on Android. Keep the existing
    // HttpURLConnection transport for ordinary GET/PUT requests and scope OkHttp to the
    // bounded PROPFIND metadata request only.
    implementation("com.squareup.okhttp3:okhttp:5.3.0")

    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("org.commonmark:commonmark:0.27.0")

    implementation("com.google.dagger:hilt-android:2.58")
    kapt("com.google.dagger:hilt-android-compiler:2.58")
    implementation("androidx.hilt:hilt-navigation-compose:1.3.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
