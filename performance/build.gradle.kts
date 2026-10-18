plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "it.ayngallery.performance"
    compileSdk = 35
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
    defaultConfig {
        minSdk = 28
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        create("benchmark") { isMinifyEnabled = true; testProguardFiles("proguard-rules.pro"); proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"); isDebuggable = false; signingConfig = signingConfigs.getByName("debug"); matchingFallbacks += "release" }
        create("profile") { initWith(getByName("benchmark")); isMinifyEnabled = false; matchingFallbacks += "benchmark" }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
androidComponents { beforeVariants(selector().all()) { it.enable = it.buildType in setOf("benchmark", "profile") } }
dependencies {
    implementation("androidx.benchmark:benchmark-macro-junit4:1.4.1")
    implementation("androidx.test:runner:1.6.2")
    implementation("androidx.arch.core:core-runtime:2.2.0")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.startup:startup-runtime:1.2.0")
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
