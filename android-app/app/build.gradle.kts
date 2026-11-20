import java.util.Properties

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
        versionCode = 500
        versionName = "0.5.0"
        ndk { abiFilters += "arm64-v8a" } // Arm only; also drops the x86_64 ExecuTorch library
    }

    // Release key lives outside the repo. Point SHIELD_KEYSTORE_PROPS (or ~/.entity-shield/keystore.properties)
    // at a file with storeFile/storePassword/keyAlias/keyPassword. Without it, release falls back to the
    // debug key so anyone can still build and sideload.
    val keyProps = Properties().apply {
        val f = file(System.getenv("SHIELD_KEYSTORE_PROPS") ?: "${System.getProperty("user.home")}/.entity-shield/keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (keyProps.getProperty("storeFile") != null) create("release") {
            storeFile = file(keyProps.getProperty("storeFile"))
            storePassword = keyProps.getProperty("storePassword")
            keyAlias = keyProps.getProperty("keyAlias")
            keyPassword = keyProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            // R8: shrinks and optimizes our code and the Compose libraries; the JNI surface ExecuTorch
            // reaches by name is kept by its consumer rules and app/proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        // QA: release code (R8, startup profile, not debuggable) so it runs as smoothly as the real
        // thing, signed like the release so it updates a release install in place, plus the adb test
        // hooks from src/debug (reachable only from the adb shell). For our test phones; never published.
        create("qa") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    // Detector models, metadata, allow-list and golden vectors come straight from training/.
    sourceSets.getByName("main").assets.srcDirs("build/generated/detector-assets")
    sourceSets.getByName("qa") {
        java.srcDirs("src/debug/java")
        kotlin.srcDirs("src/debug/java")   // AGP 9 compiles Kotlin from kotlin dirs; without this DebugReceiver is missing
        res.srcDirs("src/debug/res")
        manifest.srcFile("src/debug/AndroidManifest.xml")
    }
    androidResources { noCompress += listOf("pte", "bin") }
}

val copyDetectorAssets by tasks.registering(Copy::class) {
    val root = rootProject.projectDir.parentFile
    from(root.resolve("models/detectors")) { include("*.pte", "popular.bin", "phish.bin", "dns_lists.json") } // training/url/build_dns_lists.py
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
    // ExecuTorch: Shield's build of 1.5.1 when present (app-chosen thread count and big-core pinning,
    // .github/workflows/executorch-android.yml), otherwise the official release. Same Java API.
    val patchedEt = file("libs/executorch-android-1.5.1-shield.aar")
    if (patchedEt.exists()) {
        implementation(files(patchedEt))
        implementation("com.facebook.fbjni:fbjni:0.7.0")
        implementation("com.facebook.soloader:nativeloader:0.10.5")
        implementation("androidx.core:core-ktx:1.13.1")
    } else {
        implementation(libs.executorch)
    }
    implementation(libs.androidx.profileinstaller) // installs baseline-prof.txt on sideloaded builds too
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
