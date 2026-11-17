plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.entity.shield"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.entity.shield"
        minSdk = 29
        targetSdk = 36
        versionCode = 300
        versionName = "0.3.0"
        ndk { abiFilters += "arm64-v8a" } // Arm only; also drops the x86_64 ExecuTorch library
    }

    buildTypes {
        release {
            isMinifyEnabled = false // ExecuTorch's JNI classes must keep their names
            // Signed with the debug key so the APK installs by sideloading.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    // Detector models, metadata, allow-list and golden vectors come straight from training/.
    sourceSets.getByName("main").assets.srcDirs("build/generated/detector-assets")
    androidResources { noCompress += listOf("pte") }
}

val copyDetectorAssets by tasks.registering(Copy::class) {
    val root = rootProject.projectDir.parentFile
    from(root.resolve("models/detectors")) { include("*.pte") }
    from(root.resolve("training/sms")) { include("meta.json", "golden.json"); rename { "sms_$it" } }
    from(root.resolve("training/url")) { include("meta.json", "golden.json", "allowlist.txt"); rename { if (it == "allowlist.txt") it else "url_$it" } }
    into(layout.buildDirectory.dir("generated/detector-assets"))
}
tasks.named("preBuild") { dependsOn(copyDetectorAssets) }

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.executorch)
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
