import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("kotlin-parcelize")
    alias(libs.plugins.ksp) // Add KSP plugin
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

        val awsIotEndpoint = localProperties.getProperty("AWS_IOT_ENDPOINT") ?: "aee93mkgcstsg-ats.iot.us-east-1.amazonaws.com"
        val cognitoPoolId = localProperties.getProperty("COGNITO_POOL_ID") ?: "us-east-1:6cf5ea9f-188e-497e-9154-247c4abd71d5"
        val s3ReportsBucket = localProperties.getProperty("S3_REPORTS_BUCKET") ?: "smart-reports-372492559335-dev"
        buildConfigField("String", "AWS_IOT_ENDPOINT", "\"$awsIotEndpoint\"")
        buildConfigField("String", "COGNITO_POOL_ID", "\"$cognitoPoolId\"")
        buildConfigField("String", "S3_REPORTS_BUCKET", "\"$s3ReportsBucket\"")
    }

    signingConfigs {
        create("release") {
            val keystorePath = localProperties.getProperty("RELEASE_KEYSTORE")
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = localProperties.getProperty("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets {
        getByName("androidTest") {
            assets.srcDirs("$projectDir/schemas")
        }
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
    implementation("com.amazonaws:aws-android-sdk-s3:2.81.1")
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")

    // Room dependencies
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Unit tests (JVM)
    testImplementation(libs.junit)
    testImplementation(libs.google.truth)
    testImplementation(libs.kotlinx.coroutines.test)

    // Instrumented tests (device/emulator)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.google.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.arch.core.testing)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
