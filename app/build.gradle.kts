plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.privateapps.autoreplay"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.privateapps.autoreplay"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "0.4.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    flavorDimensions += "purpose"
    productFlavors {
        create("automatic") {
            dimension = "purpose"
            buildConfigField("boolean", "AUTOMATION_AVAILABLE", "true")
        }
        create("noAccessibility") {
            dimension = "purpose"
            applicationIdSuffix = ".diagnostic.noaccessibility"
            versionNameSuffix = "-diagnostic-no-accessibility"
            buildConfigField("boolean", "AUTOMATION_AVAILABLE", "false")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildTypes { release { isMinifyEnabled = false } }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.00"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.googlecode.libphonenumber:libphonenumber:9.0.17")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
