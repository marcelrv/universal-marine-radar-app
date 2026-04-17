plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.wire)
}

android {
    namespace = "com.marineyachtradar.mayara"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.marineyachtradar.mayara"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
    }

    // Pre-built .so from mayara-jni (produced by scripts/build_jni.sh)
    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Wire protobuf codegen — reads .proto from the mayara-server submodule.
// The path is relative to the project root. If the submodule is not initialised,
// the wire block will error: run `git submodule update --init`.
wire {
    kotlin {
        // android = false: generates plain Message subclasses (no Parcelable).
        // SpokeData is the domain model; RadarMessage is only used for decoding.
        android = false
    }
    sourcePath {
        // Local copy of the server proto with java_package option set so Wire
        // generates into a named package (default-package classes can't be
        // imported from named packages as compiled classes on the classpath).
        srcDir("src/main/proto")
    }
}

// Trigger the JNI build before the Android build (optional: allows fully automated builds).
// Comment this out and run scripts/build_jni.sh manually if cargo-ndk is not on PATH.
//
// val buildJni = tasks.register<Exec>("buildJni") {
//     commandLine("bash", "../scripts/build_jni.sh")
//     workingDir = rootProject.projectDir
// }
// tasks.named("preBuild") { dependsOn(buildJni) }

dependencies {
    // Compose BOM
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // AndroidX
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.datastore.preferences)

    // Networking
    implementation(libs.okhttp)

    // Wire / Protobuf runtime
    implementation(libs.wire.runtime)

    // Coroutines
    implementation(libs.coroutines.android)

    // Unit tests (JVM)
    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // org.json is provided by Android runtime; this JAR enables JVM unit tests
    testImplementation("org.json:json:20231013")

    // Instrumented tests
    androidTestImplementation(libs.compose.ui.test.junit4)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// Workaround for Gradle 8.x + AGP 8.8.2 configuration resolution issue
// Mark debugRuntimeClasspathCopy as non-consumable to prevent it from acting as both root and variant
afterEvaluate {
    configurations.configureEach {
        if (name.endsWith("RuntimeClasspathCopy")) {
            isCanBeConsumed = false
        }
    }
}
