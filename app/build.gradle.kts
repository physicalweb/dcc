// Forcing removal of local AIDL config to resolve duplicate class error.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("kotlin-parcelize")
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

android {
    namespace = "com.artmedical.dcc"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.artmedical.dcc"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val awsIotEndpoint = localProperties.getProperty("AWS_IOT_ENDPOINT") ?: ""
        val cognitoPoolId = localProperties.getProperty("COGNITO_POOL_ID") ?: ""
        buildConfigField("String", "AWS_IOT_ENDPOINT", "\"$awsIotEndpoint\"")
        buildConfigField("String", "COGNITO_POOL_ID", "\"$cognitoPoolId\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    api(project(":shared-api"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.amazonaws:aws-android-sdk-iot:2.81.1")
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
}
