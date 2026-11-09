plugins {
    id("mihon.android.application")
}

val probeLiteRtVersion = providers.gradleProperty("mcv2.probe.litertVersion")
    .orElse(libs.versions.litert.version)

android {
    namespace = "eu.kanade.translation.mcv2probe"

    packaging {
        jniLibs {
            // Keep extractNativeLibs=true effective: QNN HTP needs the skel as a real
            // file on disk (nativeLibraryDir) for dsp_skel_dir discovery.
            useLegacyPackaging = true
        }
    }

    defaultConfig {
        applicationId = "eu.kanade.translation.mcv2probe"
        versionCode = 1
        versionName = "1.0"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }
}

dependencies {
    implementation(libs.litert)
    implementation(libs.litert.gpu)
    implementation(libs.litert.npu.qualcomm)
    implementation(libs.onnxruntime.android.baseline)
}

// The harness A/Bs LiteRT lines via -Pmcv2.probe.litertVersion; the catalog pin
// (litert-version) is the default when the property is absent. Forcing the
// version for the whole group keeps core/gpu/npu line-coupled.
val probeLiteRt = probeLiteRtVersion.get()
configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "com.google.ai.edge.litert") {
            useVersion(probeLiteRt)
        }
    }
}

// Match the app workaround for LiteRT metadata built with Kotlin 2.4.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.freeCompilerArgs.add("-Xskip-metadata-version-check")
}
