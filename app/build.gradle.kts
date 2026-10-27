import java.security.MessageDigest

plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "it.lumetv.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "it.lumetv.app"
        minSdk = 23
        targetSdk = 36
        versionCode = 8
        versionName = "0.3.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        debug {
            buildConfigField("String", "AURA_TRUSTED_CERTIFICATE_SHA256", "\"" + providers.gradleProperty("auraTrustedCertificateSha256")
                .orElse("3ceae7f474128b6c7d9e2197497a34e7e8db301e984ff1bab2047703fac80aeb").get() + "\"")
        }
        release {
            buildConfigField("String", "AURA_TRUSTED_CERTIFICATE_SHA256", "\"" + providers.gradleProperty("auraTrustedCertificateSha256").orElse("").get() + "\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    packaging { resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1") }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.systemProperty("lume.live", providers.gradleProperty("liveSources").orElse("false").get())
            it.systemProperty("lume.liveReport", layout.buildDirectory.file("reports/live-resolvers.json").get().asFile.absolutePath)
            it.systemProperty("lume.guideFile", providers.gradleProperty("guideFile").orElse("").get())
        }
    }
    sourceSets.getByName("test").resources.srcDir("src/main/assets")
    sourceSets.getByName("test").resources.srcDir(rootProject.file("test-fixtures"))
    buildFeatures { buildConfig = true }
    lint { abortOnError = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

// Record the actual artifacts used by the APK, including the desugared JDK.
// tools/dependency_sources.py checks these against the source distribution lock.
tasks.register("writeReleaseDependencies") {
    val report = layout.buildDirectory.file("reports/release-dependencies.json")
    outputs.file(report)
    outputs.upToDateWhen { false }
    doLast {
        val artifacts = listOf("releaseRuntimeClasspath", "coreLibraryDesugaring").flatMap { name ->
            configurations.getByName(name).resolvedConfiguration.resolvedArtifacts
                .filter { it.id.componentIdentifier !is org.gradle.api.artifacts.component.ProjectComponentIdentifier }
                .map { artifact ->
                    mapOf("coordinate" to artifact.moduleVersion.id.toString(),
                        "artifact" to artifact.file.name,
                        "sha256" to MessageDigest.getInstance("SHA-256")
                            .digest(artifact.file.readBytes()).joinToString("") { "%02x".format(it) })
                }
        }.distinct().sortedBy { it["coordinate"] }
        report.get().asFile.apply {
            parentFile.mkdirs()
            writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(artifacts)) + "\n")
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.tukaani:xz:1.9")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}
