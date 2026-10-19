plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val lgplSources by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies { add(lgplSources.name, "de.sciss:jump3r:1.0.5:sources@jar") }

val prepareLicenseAssets by tasks.registering(Copy::class) {
    from(rootProject.file("LICENSE.md"), rootProject.file("licenses/GPL-3.0.txt"), rootProject.file("docs/LGPL_RELINKING.md"))
    into(layout.buildDirectory.dir("generated/legal-assets"))
}
tasks.named("preBuild").configure { dependsOn(prepareLicenseAssets) }

tasks.register<Zip>("lgplRelinkingKit") {
    group = "distribution"
    description = "Complete reconstruction sources and jump3r sources to accompany distributed APKs."
    archiveFileName.set(providers.provider { "DSGallery-${android.defaultConfig.versionName}-relinking.zip" })
    destinationDirectory.set(rootProject.layout.projectDirectory.dir("dist"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(rootProject.projectDir) {
        include("app/src/**", "app/schemas/**", "app/build.gradle.kts", "app/proguard-rules.pro",
            "performance/build.gradle.kts", "performance/proguard-rules.pro", "performance/src/**",
            "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat",
            "gradle/wrapper/**", "licenses/**", "assets/audio/**", "LICENSE.md", "THIRD_PARTY_NOTICES.md", "docs/LGPL_RELINKING.md")
    }
    from(lgplSources) { into("third_party/jump3r") }
}

android {
    namespace = "it.ayngallery"
    compileSdk = 35
    defaultConfig {
        applicationId = "it.ayngallery"
        minSdk = 28
        targetSdk = 35
        testInstrumentationRunner = "it.ayngallery.GifStressInstrumentation"
        versionCode = 212
        versionName = "0.1.0-beta.126"
    }
    buildFeatures { compose = true }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/legal-assets"))
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Local performance builds preserve the existing development signing identity.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            isDebuggable = false
        }
        create("profile") {
            initWith(getByName("benchmark"))
            isMinifyEnabled = false
            isShrinkResources = false
            matchingFallbacks += "benchmark"
        }
    }
    packaging.resources.merges += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/NOTICE")
    testOptions { unitTests.isIncludeAndroidResources = true }
    // Keep every language available offline, including installations from an app bundle.
    bundle { language { enableSplit = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("io.coil-kt.coil3:coil-compose:3.1.0")
    implementation("io.coil-kt.coil3:coil-gif:3.1.0")
    implementation("com.github.bumptech.glide:gifdecoder:4.16.0") // Real frame selection; Coil remains the ordinary image loader.
    implementation("org.apache.ftpserver:ftpserver-core:1.2.1") // FTP protocol, passive data channels and authentication; SAF remains app-owned.
    implementation("org.apache.mina:mina-core:2.2.9") // Includes subsequent MINA security fixes; FTP does not use object serialization.
    implementation("com.hierynomus:smbj:0.15.0") // Signed SMB2/3 browsing; credentials stay in memory.
    implementation("io.coil-kt.coil3:coil-video:3.1.0")
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.6.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.6.1")
    implementation("androidx.media3:media3-session:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
    implementation("androidx.media3:media3-transformer:1.6.1")
    implementation("androidx.media3:media3-effect:1.6.1")
    implementation("de.sciss:jump3r:1.0.5") // MP3 encoding; Android's built-in exporter only supplies AAC.
    testImplementation("junit:junit:4.13.2")
    testImplementation("commons-net:commons-net:3.12.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.work:work-testing:2.10.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    // Bound accumulated Android SDK sandboxes on long Windows runs as well.
    maxHeapSize = "1g"
    forkEvery = 20
    // Native Robolectric sandboxes must not share JNI classes across test suites.
    if (System.getProperty("os.name").lowercase().contains("linux")) {
        forkEvery = 1
        maxParallelForks = 2
    }
    // Balance whole classes by test count, keeping native Robolectric sandboxes isolated.
    providers.gradleProperty("testShard").orNull?.let { value ->
        val parts = value.split("/").map(String::toInt)
        require(parts.size == 2 && parts[1] > 0 && parts[0] in 0 until parts[1])
        val groups = List(parts[1]) { mutableListOf<String>() }
        val weights = IntArray(parts[1])
        fileTree("src/test/java").matching { include("**/*Test.kt") }.files
            .map { it to Regex("@Test\\b").findAll(it.readText()).count().coerceAtLeast(1) }
            .sortedWith(compareByDescending<Pair<File, Int>> { it.second }.thenBy { it.first.name })
            .forEach { (file, weight) ->
                val group = weights.indices.minBy { weights[it] }
                val packageName = Regex("(?m)^package ([\\w.]+)").find(file.readText())!!.groupValues[1]
                groups[group].add("$packageName.${file.nameWithoutExtension}"); weights[group] += weight
            }
        filter { groups[parts[0]].forEach { includeTestsMatching(it) } }
    }
    testLogging {
        events("started", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
}
