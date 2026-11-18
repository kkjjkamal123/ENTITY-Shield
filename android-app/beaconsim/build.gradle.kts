// Test-only app: imitates what infected apps do on the network so Network Guard can be demonstrated
// and tested end to end. It only performs DNS lookups of made-up names; it never sends data anywhere.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "app.entity.beaconsim"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.entity.beaconsim"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes { release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("debug") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
