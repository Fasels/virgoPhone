import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val hasReleaseKeystore = keystorePropertiesFile.exists()
val testClientNumber = providers.gradleProperty("mmsTestClientNumber").orNull
val releaseClientNumber = providers.gradleProperty("releaseClientNumber").orNull
require(testClientNumber == null || releaseClientNumber == null) {
    "Test and release client identities cannot be selected together"
}
require(releaseClientNumber == null || releaseClientNumber.toIntOrNull()?.let { it in 1..5 } == true) {
    "releaseClientNumber must be between 1 and 5"
}
val agentBaseUrl = providers.gradleProperty("agentBaseUrl").orNull ?: "https://api.syzygium.net"
require(testClientNumber == null || testClientNumber.toIntOrNull()?.let { it in 1..5 } == true) {
    "mmsTestClientNumber must be between 1 and 5"
}
val requestedReleaseBuild = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
val keystoreProperties = Properties().apply {
    if (hasReleaseKeystore) {
        keystorePropertiesFile.inputStream().use(::load)
    } else if (requestedReleaseBuild) {
        error("Missing keystore.properties. Create it before building a release APK.")
    }
}

android {
    namespace = "com.example.virgo"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = when {
            releaseClientNumber != null -> "com.example.virgo.client${releaseClientNumber}"
            testClientNumber != null -> "com.example.virgo.client${testClientNumber}.mmstest"
            else -> "com.example.virgo"
        }
        minSdk = 27
        targetSdk = 36
        versionCode = when {
            releaseClientNumber != null -> 3
            testClientNumber != null -> 2
            else -> 1
        }
        versionName = when {
            releaseClientNumber != null -> "1.1.1"
            testClientNumber != null -> "1.1.0-mms-20261006"
            else -> "1.0"
        }
        manifestPlaceholders["agentAppLabel"] = when {
            releaseClientNumber != null -> "客服端${releaseClientNumber}"
            testClientNumber != null -> "客服端${testClientNumber} MMS测试"
            else -> "@string/app_name"
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = releaseClientNumber == null
            buildConfigField(
                "String",
                "AGENT_BASE_URL",
                "\"$agentBaseUrl\"",
            )
        }
        release {
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            buildConfigField(
                "String",
                "AGENT_BASE_URL",
                "\"$agentBaseUrl\"",
            )
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("kf-debug.apk")
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
