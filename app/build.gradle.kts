@file:Suppress("ChromeOsAbiSupport")

import com.android.build.api.artifact.SingleArtifact
import mihon.buildlogic.getBuildTime
import mihon.buildlogic.getCommitCount
import mihon.buildlogic.getGitSha
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

plugins {
    id("mihon.android.application")
    id("mihon.android.application.compose")
    id("com.github.zellius.shortcut-helper")
    kotlin("plugin.serialization")
    alias(libs.plugins.aboutLibraries)
}

shortcutHelper.setFilePath("./shortcuts.xml")

val supportedAbis = setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
val buildTime = providers.gradleProperty("validationBuildTime").orNull ?: getBuildTime()

android {
    namespace = "eu.kanade.tachiyomi"

    defaultConfig {
        applicationId = "app.kanade.tachiyomi.vibe"

        versionCode = 20
        versionName = "0.17.1"

        buildConfigField("String", "COMMIT_COUNT", "\"${getCommitCount()}\"")
        buildConfigField("String", "COMMIT_SHA", "\"${getGitSha()}\"")
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
        buildConfigField("boolean", "INCLUDE_UPDATER", "false")
        buildConfigField("boolean", "PREVIEW", "false")
        // Paddle batching is production-pinned to B1. Staged/debug builds may
        // request a larger size, but the device policy must approve the exact
        // provider/width/batch cell or the explicit CPU B1 emergency path wins.
        buildConfigField("boolean", "PADDLE_BATCHING_STAGED", "false")
        buildConfigField("int", "PADDLE_BATCHING_REQUESTED_BATCH", "1")

        ndk {
            abiFilters += supportedAbis
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        if (!project.hasProperty("includeManualMeasurementBenches")) {
            testInstrumentationRunnerArguments["notAnnotation"] =
                "eu.kanade.translation.diagnostics.ManualMeasurementBench"
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include(*supportedAbis.toTypedArray())
            isUniversalApk = true
        }
    }

    androidResources {
        noCompress += listOf("tflite", "gz")
    }

    signingConfigs {
        named("debug") {
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        named("debug") {
            versionNameSuffix = "-${getCommitCount()}"
            applicationIdSuffix = ".debug"
            isPseudoLocalesEnabled = true
            buildConfigField("boolean", "PADDLE_BATCHING_STAGED", "true")
            buildConfigField("int", "PADDLE_BATCHING_REQUESTED_BATCH", "4")
        }
        named("release") {
            isShrinkResources = true
            isMinifyEnabled = true
            proguardFiles("proguard-android-optimize.txt", "proguard-rules.pro")
        }
        create("preview") {
            initWith(getByName("release"))
            buildConfigField("boolean", "PREVIEW", "true")

            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks.add("release")
            val debugType = getByName("debug")
            versionNameSuffix = debugType.versionNameSuffix
            applicationIdSuffix = debugType.applicationIdSuffix
        }
        create("benchmark") {
            initWith(getByName("release"))

            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks.add("release")
            isDebuggable = false
            isProfileable = true
            versionNameSuffix = "-benchmark"
            applicationIdSuffix = ".debug"
            buildConfigField("boolean", "PADDLE_BATCHING_STAGED", "true")
            buildConfigField("int", "PADDLE_BATCHING_REQUESTED_BATCH", "4")
        }
    }

    sourceSets {
        getByName("preview").res.srcDirs("src/debug/res")
        getByName("benchmark").res.srcDirs("src/debug/res")
    }

    flavorDimensions.add("default")

    productFlavors {
        create("standard") {
            buildConfigField("boolean", "INCLUDE_UPDATER", "true")
            dimension = "default"
        }
        create("dev") {
            // Include pseudolocales: https://developer.android.com/guide/topics/resources/pseudolocales
            resourceConfigurations.addAll(listOf("en", "en_XA", "ar_XB", "xxhdpi"))
            dimension = "default"
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = true
        resources.excludes.addAll(
            listOf(
                "kotlin-tooling-metadata.json",
                "META-INF/DEPENDENCIES",
                "LICENSE.txt",
                "META-INF/LICENSE",
                "META-INF/**/LICENSE.txt",
                "META-INF/*.properties",
                "META-INF/**/*.properties",
                "META-INF/README.md",
                "META-INF/NOTICE",
                "META-INF/*.version",
                "assets/models/ocr/paddle-v6-small/inference.json",
            ),
        )
        // TachiyomiAT: the QNN debug override experiment — dropping newer QAIRT
        // libQnn*.so files into app/src/debug/jniLibs/arm64-v8a/ lets a debug
        // build ship a newer QNN runtime than the onnxruntime-android-qnn AAR
        // bundles. Release builds carry no such files, so this never fires there.
        jniLibs.pickFirsts.addAll(
            listOf(
                "**/libQnnHtp.so",
                "**/libQnnHtpPrepare.so",
                "**/libQnnSystem.so",
                "**/libQnnGpu.so",
                "**/libQnnDsp.so",
                "**/libQnnHtpV*Stub.so",
                "**/libQnnHtpV*Skel.so",
            ),
        )
    }

    dependenciesInfo {
        includeInApk = false
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true

        // Disable some unused things
        aidl = false
        renderScript = false
        shaders = false
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
            "-opt-in=coil3.annotation.ExperimentalCoilApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
            "-opt-in=kotlinx.coroutines.InternalCoroutinesApi",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
        )
    }
}

// TachiyomiAT: static guard against the silent-JUnit-skip quirk (found in a
// test-suite audit). A test declared as an expression
// body — fun `t`() = runBlocking { ... } — gets its return type inferred; if
// inference yields anything but Unit, the method compiles to a non-void JVM
// method that JUnit silently skips while it still counts as a suite member.
// The cure is runBlocking<Unit>. Bare statement/block forms and explicit
// `runBlocking<Unit>` never match the pattern below. Known occurrences are
// exempted via config/runblocking-allowlist.txt.

val testRunBlockingAllowlist = file("config/runblocking-allowlist.txt")

/**
 * Count actual @Test-annotated functions and their quarantine tags. Test
 * sources keep one test class per file (helper classes may also be present),
 * so a class-level quarantine tag excludes that file's test methods.
 */
fun testMethodCounts(): Pair<Int, Int> {
    val testAnnotation = Regex("""^@Test(?:\s*\([^)]*\))?$""")
    val quarantineTag = Regex("""@Tag\s*\(\s*"quarantined-flaky"\s*\)""")
    val classDeclaration = Regex(
        """^(?:(?:open|abstract|internal|private|public|sealed|data|enum|annotation|inner|final)\s+)*class\b""",
    )
    val functionDeclaration = Regex(
        """^(?:(?:public|private|internal|protected|suspend|open|override|final|inline|operator|infix|tailrec|external)\s+)*fun\b""",
    )
    val inlineFunctionDeclaration = Regex("""\bfun\b""")
    val testSources = fileTree("src/test") { include("**/*.kt") }.files
    var testMethodCount = 0
    var quarantinedTestMethodCount = 0

    testSources.forEach { sourceFile ->
        val lines = sourceFile.readLines()
        val classIsQuarantined = lines.indices.any { index ->
            if (!quarantineTag.containsMatchIn(lines[index])) {
                return@any false
            }
            val nextDeclaration = (index + 1 until lines.size)
                .firstOrNull { next ->
                    val line = lines[next].trim()
                    line.isNotEmpty() && !line.startsWith("//") && !line.startsWith("@")
                }
            nextDeclaration != null && classDeclaration.containsMatchIn(lines[nextDeclaration].trim())
        }
        val pendingAnnotations = mutableListOf<String>()

        fun countTestMethod(annotations: List<String>, classQuarantined: Boolean) {
            if (annotations.any(testAnnotation::matches)) {
                testMethodCount++
                if (classQuarantined || annotations.any(quarantineTag::containsMatchIn)) {
                    quarantinedTestMethodCount++
                }
            }
        }

        lines.forEach { sourceLine ->
            val line = sourceLine.trim()
            when {
                line.isEmpty() || line.startsWith("//") || line.startsWith("/*") || line.startsWith("*") -> Unit
                line.startsWith("@") && inlineFunctionDeclaration.containsMatchIn(line) -> {
                    val functionStart = inlineFunctionDeclaration.find(line)?.range?.first ?: line.length
                    countTestMethod(pendingAnnotations + line.substring(0, functionStart).trim(), classIsQuarantined)
                    pendingAnnotations.clear()
                }
                line.startsWith("@") -> pendingAnnotations += line
                functionDeclaration.containsMatchIn(line) -> {
                    countTestMethod(pendingAnnotations, classIsQuarantined)
                    pendingAnnotations.clear()
                }
                else -> pendingAnnotations.clear()
            }
        }
    }

    return testMethodCount to quarantinedTestMethodCount
}

tasks.register("checkTestRunBlocking") {
    group = "verification"
    description = "Fails when a unit test uses the expression-body `= runBlocking {` form, " +
        "which JUnit silently skips if inference produces a non-Unit return. " +
        "Declare runBlocking<Unit> or add an exemption to config/runblocking-allowlist.txt."

    inputs.files(fileTree("src/test") { include("**/*.kt") })
    inputs.file(testRunBlockingAllowlist)

    doLast {
        val allowlist = testRunBlockingAllowlist.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

        // Defect form: `= runBlocking {` or `= runBlocking` at end of line.
        val risky = Regex("""=\s*(kotlinx\.coroutines\.)?runBlocking\s*(\{|$)""")

        val offenders = mutableListOf<String>()
        val testAnnotation = Regex("""@Test\b(?:\s*\([^)]*\))?""")
        val functionDeclaration = Regex(
            """^(?:(?:public|private|protected|internal|suspend|open|override|final|inline|operator|infix|tailrec|external)\s+)*fun\b""",
        )
        fileTree("src/test") { include("**/*.kt") }.forEach { file ->
            val relPath = file.toRelativeString(projectDir).replace('\\', '/')
            if (allowlist.any { relPath.contains(it) }) return@forEach
            var pendingTestAnnotation = false
            file.readLines().forEachIndexed { index, line ->
                val trimmedLine = line.trim()
                if (testAnnotation.containsMatchIn(trimmedLine)) pendingTestAnnotation = true
                val match = risky.find(line)
                if (match != null && pendingTestAnnotation && "fun" in line.substring(0, match.range.first)) {
                    offenders += "$relPath:${index + 1}: expression-body `= runBlocking` lets the " +
                        "compiler infer the test's JVM return type; a non-Unit result is silently " +
                        "skipped by JUnit. Declare runBlocking<Unit> " +
                        "(or extend app/config/runblocking-allowlist.txt with an audit note)."
                }
                if (functionDeclaration.containsMatchIn(trimmedLine)) {
                    pendingTestAnnotation = false
                } else if (
                    trimmedLine.isNotEmpty() &&
                    !trimmedLine.startsWith("//") &&
                    !trimmedLine.startsWith("/*") &&
                    !trimmedLine.startsWith("*") &&
                    !trimmedLine.startsWith("@")
                ) {
                    pendingTestAnnotation = false
                }
            }
        }

        if (offenders.isNotEmpty()) {
            throw GradleException(
                "checkTestRunBlocking found ${offenders.size} risky expression-body runBlocking " +
                    "test declaration(s):\n" + offenders.joinToString("\n"),
            )
        }
    }
}

tasks.named("check") {
    dependsOn("checkTestRunBlocking")
}

// The count-parity task is a finalizer so each full JVM suite run checks its
// own XML, including when Gradle considers the test task up-to-date. Filtered
// diagnostics and the opt-in quarantined run intentionally skip this default
// suite parity check.
val countParitySuiteNames = setOf("testDevReleaseUnitTest", "testStandardReleaseUnitTest")
countParitySuiteNames.forEach { suiteTaskName ->
    tasks.register("${suiteTaskName}CountParity") {
        group = "verification"
        description = "Checks declared app test methods against this suite's JUnit XML count."
        mustRunAfter(suiteTaskName)

        doLast {
            val suiteTask = tasks.named<org.gradle.api.tasks.testing.Test>(suiteTaskName).get()
            val (declaredTests, quarantinedTests) = testMethodCounts()
            val expectedTests = declaredTests - quarantinedTests
            logger.lifecycle(
                "Test method inventory for ${suiteTask.name}: declared=$declaredTests, " +
                    "quarantined=$quarantinedTests, expected=$expectedTests.",
            )
            val commandLineFilterActive = listOf(
                "getCommandLineIncludePatterns",
                "getCommandLineExcludePatterns",
            ).any { getterName ->
                runCatching {
                    val getter = suiteTask.filter.javaClass.methods
                        .firstOrNull { it.name == getterName && it.parameterCount == 0 }
                    (getter?.invoke(suiteTask.filter) as? Collection<*>)?.isNotEmpty() == true
                }.getOrDefault(false)
            }
            if (
                suiteTask.filter.includePatterns.isNotEmpty() ||
                suiteTask.filter.excludePatterns.isNotEmpty() ||
                commandLineFilterActive
            ) {
                logger.lifecycle("Skipping test count parity for filtered diagnostics: ${suiteTask.name}")
                return@doLast
            }
            if (project.hasProperty("includeQuarantinedTests")) {
                logger.lifecycle("Skipping default test count parity for the opt-in quarantined run.")
                return@doLast
            }

            val reportDirectory = suiteTask.reports.junitXml.outputLocation.get().asFile
            val xmlFiles = reportDirectory.walkTopDown()
                .filter { it.isFile && it.extension.equals("xml", ignoreCase = true) }
                .toList()
            if (xmlFiles.isEmpty()) {
                throw GradleException("${suiteTask.name} produced no JUnit XML in $reportDirectory")
            }

            val testsAttribute = Regex("(?s)<testsuite\\b[^>]*\\btests=\"(\\d+)\"")
            val actualTests = xmlFiles.sumOf { xmlFile ->
                val match = testsAttribute.find(xmlFile.readText())
                    ?: throw GradleException("No tests attribute found in ${xmlFile.path}")
                match.groupValues[1].toInt()
            }
            if (actualTests != expectedTests) {
                throw GradleException(
                    "Test count parity failed for ${suiteTask.name}: declared=$declaredTests, " +
                        "quarantined=$quarantinedTests, expected=$expectedTests, JUnit XML=$actualTests.",
                )
            }
            logger.lifecycle(
                "Test count parity passed for ${suiteTask.name}: declared=$declaredTests, " +
                    "quarantined=$quarantinedTests, JUnit XML=$actualTests.",
            )
        }
    }
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    if (name in countParitySuiteNames) finalizedBy("${name}CountParity")
    systemProperty(
        "translation.boundary.srcMainJava",
        project.layout.projectDirectory.dir("src/main/java").asFile.absolutePath,
    )
}

dependencies {
    implementation(projects.i18n)
    implementation(projects.i18nAt)
    implementation(projects.core.archive)
    implementation(projects.core.common)
    implementation(projects.coreMetadata)
    implementation(projects.sourceApi)
    implementation(projects.sourceLocal)
    implementation(projects.data)
    implementation(projects.domain)
    implementation(projects.presentationCore)
    implementation(projects.presentationWidget)

    // Compose
    implementation(compose.activity)
    implementation(compose.foundation)
    implementation(compose.material3.core)
    implementation(compose.material.icons)
    implementation(compose.animation)
    implementation(compose.animation.graphics)
    debugImplementation(compose.ui.tooling)
    implementation(compose.ui.tooling.preview)
    implementation(compose.ui.util)

    implementation(androidx.interpolator)

    implementation(androidx.paging.runtime)
    implementation(androidx.paging.compose)

    implementation(libs.bundles.sqlite)

    implementation(kotlinx.reflect)
    implementation(kotlinx.immutables)

    implementation(platform(kotlinx.coroutines.bom))
    implementation(kotlinx.bundles.coroutines)

    // AndroidX libraries
    implementation(androidx.annotation)
    implementation(androidx.appcompat)
    implementation(androidx.biometricktx)
    implementation(androidx.constraintlayout)
    implementation(androidx.corektx)
    implementation(androidx.splashscreen)
    implementation(androidx.recyclerview)
    implementation(androidx.viewpager)
    implementation(androidx.profileinstaller)

    implementation(androidx.bundles.lifecycle)

    // Job scheduling
    implementation(androidx.workmanager)

    // RxJava
    implementation(libs.rxjava)

    // Networking
    implementation(libs.bundles.okhttp)
    implementation(libs.okio)
    implementation(libs.conscrypt.android) // TLS 1.3 support for Android < 10

    // Data serialization (JSON, protobuf, xml)
    implementation(kotlinx.bundles.serialization)

    // HTML parser
    implementation(libs.jsoup)

    // Disk
    implementation(libs.disklrucache)
    implementation(libs.unifile)

    // Preferences
    implementation(libs.preferencektx)

    // Dependency injection
    implementation(libs.injekt)

    // Image loading
    implementation(platform(libs.coil.bom))
    implementation(libs.bundles.coil)
    implementation(libs.subsamplingscaleimageview) {
        exclude(module = "image-decoder")
    }
    implementation(libs.image.decoder)

    // UI libraries
    implementation(libs.material)
    implementation(libs.flexible.adapter.core)
    implementation(libs.photoview)
    implementation(libs.directionalviewpager) {
        exclude(group = "androidx.viewpager", module = "viewpager")
    }
    implementation(libs.insetter)
    implementation(libs.bundles.richtext)
    implementation(libs.aboutLibraries.compose)
    implementation(libs.bundles.voyager)
    implementation(libs.compose.materialmotion)
    implementation(libs.swipe)
    implementation(libs.compose.webview)
    implementation(libs.compose.grid)

    // Logging
    implementation(libs.logcat)

    // Shizuku
    implementation(libs.bundles.shizuku)

    // Tests
    testImplementation(libs.bundles.test)
    // Android instrumentation tests (the renderer fixture uses AndroidJUnit4).
    androidTestImplementation(androidx.test.ext)
    // The runner must be packaged into the test APK; without it am instrument
    // fails with ClassNotFoundException on androidx.test.runner.AndroidJUnitRunner.
    androidTestImplementation(androidx.test.runner)

    // For detecting memory leaks; see https://square.github.io/leakcanary/
    // debugImplementation(libs.leakcanary.android)
    implementation(libs.leakcanary.plumber)

    testImplementation(kotlinx.coroutines.test)

    // TachiyomiAT
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.text.recognition.japanese)
    implementation(libs.mlkit.text.recognition.korean)
    implementation(libs.mlkit.text.recognition.chinese)
    implementation(libs.mlkit.text.translate)
    implementation(libs.onnxruntime.android)
    implementation(libs.litert)
    implementation(libs.litert.gpu)
    implementation(libs.litert.npu.qualcomm)
    implementation(libs.jtokkit)
    implementation(libs.opencv)
}

// Temporary compatibility workaround until the project upgrades its Kotlin plugin to 2.4+:
// LiteRT 2.3.0 publishes Kotlin 2.4 metadata while the app is built with Kotlin 2.2.20.
// The CompiledModel methods consumed here use the stable JVM API surface.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.freeCompilerArgs.add("-Xskip-metadata-version-check")
}

configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "androidx.lifecycle") {
            useVersion("2.8.7")
        }
    }
}

// Model binaries are not tracked in Git; every APK packages them from
// app/src/main/assets/models. Provision them automatically so a fresh clone
// or worktree never ships an APK that silently misses translation models.
val mcv2GpuModelPath = providers.gradleProperty("models.mcv2.gpu")
val mcv2GpuAssetDirectory = layout.buildDirectory.dir("generated/mcv2-debug-assets")
android.sourceSets.getByName("debug").assets.srcDir(mcv2GpuAssetDirectory)

val stageMcv2GpuModel by tasks.registering {
    group = "build setup"
    description = "Stages a local MCV2 LiteRT model into debug APK assets (-Pmodels.mcv2.gpu=<file>)."
    doLast {
        val destination = mcv2GpuAssetDirectory.get().file("models/coloring/manga_colorize_gpu_fp32.tflite").asFile
        val configuredPath = mcv2GpuModelPath.orNull
        if (configuredPath == null) {
            if (destination.exists() && !destination.delete()) {
                throw GradleException("Could not remove stale MCV2 LiteRT debug asset: ${destination.absolutePath}")
            }
            logger.lifecycle("[mcv2] no local LiteRT graph configured; removed any stale debug asset")
            return@doLast
        }

        val source = rootProject.file(configuredPath)
        if (!source.isFile || source.length() == 0L) {
            if (destination.exists() && !destination.delete()) {
                throw GradleException("Could not remove stale MCV2 LiteRT debug asset: ${destination.absolutePath}")
            }
            throw GradleException("MCV2 LiteRT model does not exist or is empty: ${source.absolutePath}")
        }
        destination.parentFile.mkdirs()
        if (source.canonicalFile != destination.canonicalFile) {
            source.copyTo(destination, overwrite = true)
        }
        logger.lifecycle("[mcv2] staged local LiteRT model for debug APK: ${destination.absolutePath}")
    }
}

val qnnLibraryHashes = linkedMapOf(
    "libQnnHtp.so" to "09e930b6af975bae23bcf6965ac2ce60f6b999aff37cb7eabe9b4d7c1bc3f264",
    "libQnnSystem.so" to "b30bd9880dc48f6c5349185a7d45b326f8a064b10072e60094354fa23d9a2027",
    "libQnnHtpV75Stub.so" to "8be2bf0b0c6839d3e3bf9d8435e9b92d3fde0ccdea343d89960d75285ad28dab",
    "libQnnHtpV75Skel.so" to "2f6cfe5aae553d8ec88b6741bf8598a557375eacfc58056c2667fc43fda64f6a",
)

fun entrySha256(zip: ZipFile, entry: ZipEntry): String {
    val stream = zip.getInputStream(entry)
    try {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    } finally {
        stream.close()
    }
}

fun verifyAsset(apkName: String, zip: ZipFile, relativePath: String, expectedSha256: String, problems: MutableList<String>) {
    val asset = zip.getEntry("assets/$relativePath")
    if (asset == null) {
        problems += "$apkName: staged artifact missing (assets/$relativePath)"
    } else {
        val hash = entrySha256(zip, asset)
        if (hash != expectedSha256) {
            problems += "$apkName: assets/$relativePath is $hash, expected $expectedSha256"
        }
    }
}

// TachiyomiAT: the experimental SM8650 NPU route stages its artifact and QNN
// libraries from local files (-Pmodels.mcv2.npu / -Pmodels.mcv2.npu.libs), each
// byte-verified against the device-validated handoff so bad staging fails the build.
val mcv2NpuModelPath = providers.gradleProperty("models.mcv2.npu")
val mcv2NpuW8a8ModelPath = providers.gradleProperty("models.mcv2.npu.w8a8")
val upscalingNpuModelPath = providers.gradleProperty("models.upscaling.npu")
val mcv2NpuLibraryDirectoryPath = providers.gradleProperty("models.mcv2.npu.libs")
val mcv2NpuAssetDirectory = layout.buildDirectory.dir("generated/mcv2-npu-debug-assets")
val mcv2NpuJniLibsDirectory = layout.buildDirectory.dir("generated/mcv2-npu-jniLibs")
android.sourceSets.getByName("debug").assets.srcDir(mcv2NpuAssetDirectory)
android.sourceSets.getByName("debug").jniLibs.srcDir(mcv2NpuJniLibsDirectory)

val mcv2NpuArtifactSha256 = "d3d26e5e1af43213a8b6d9a9cca23b30e1f432e5f2dbf02398ef31199a7ff1f4"
val mcv2NpuArtifactBytes = 79_446_056L
val mcv2NpuAssetRelativePath = "models/coloring/manga_colorize_npu_sm8650_fp32.tflite"

val mcv2NpuW8a8ArtifactSha256 = "d687f414868e11b47049ac0566b7087f767abcb2a7ca02d0edad01e1437d4ac9"
val mcv2NpuW8a8ArtifactBytes = 55_615_832L
val mcv2NpuW8a8AssetRelativePath = "models/coloring/manga_colorize_npu_sm8650_w8a8.tflite"

val upscalingNpuArtifactSha256 = "4cf4ba774f2cae4e998ab1989a5947b3dbdc920ab24ce94c10aaebe73b90e331"
val upscalingNpuArtifactBytes = 1_050_928L
val upscalingNpuAssetRelativePath = "models/upscaling/quicksrnetlarge_sm8650_float_apply_plugin.tflite"

val mangaLightNpuModelPath = providers.gradleProperty("models.mangaLight.npu")
val mangaLightHybridModelsDir = providers.gradleProperty("models.mangaLight.hybrid.dir")
val mangaLightNpuArtifactSha256 = "0e4e38dc7f1c319728722c75705e9e948eebca72261191f5629d59d1ab962737"
val mangaLightNpuArtifactBytes = 115_000_872L
val mangaLightNpuAssetRelativePath = "models/coloring/manga_light_colorizer_npu_sm8650_fp32.tflite"

val mcv2NpuLibraries = qnnLibraryHashes
val mcv2NpuStagingEnabled = mcv2NpuModelPath.isPresent ||
    mcv2NpuW8a8ModelPath.isPresent ||
    upscalingNpuModelPath.isPresent ||
    mangaLightNpuModelPath.isPresent ||
    mangaLightHybridModelsDir.isPresent ||
    mcv2NpuLibraryDirectoryPath.isPresent

fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

val stageMcv2NpuModel by tasks.registering {
    group = "build setup"
    description = "Stages the SM8650 NPU MCV2 FP32 artifact into debug APK assets (-Pmodels.mcv2.npu=<file>), " +
        "verifying the device-validated hash."
    doLast {
        val destination = mcv2NpuAssetDirectory.get().file(mcv2NpuAssetRelativePath).asFile
        val configuredPath = mcv2NpuModelPath.orNull
        if (configuredPath == null) {
            if (destination.exists() && !destination.delete()) {
                throw GradleException("Could not remove stale MCV2 NPU debug asset: ${destination.absolutePath}")
            }
            logger.lifecycle("[mcv2] no local NPU artifact configured; removed any stale debug asset")
            return@doLast
        }

        val source = rootProject.file(configuredPath)
        if (!source.isFile || source.length() != mcv2NpuArtifactBytes) {
            throw GradleException(
                "MCV2 NPU artifact missing or wrong size " +
                    "(${if (source.isFile) "${source.length()} bytes" else "absent"}, expected $mcv2NpuArtifactBytes): " +
                    source.absolutePath,
            )
        }
        val hash = sha256Hex(source)
        if (hash != mcv2NpuArtifactSha256) {
            throw GradleException(
                "MCV2 NPU artifact hash mismatch ($hash); expected the device-validated " +
                    "apply_plugin build $mcv2NpuArtifactSha256",
            )
        }
        destination.parentFile.mkdirs()
        if (source.canonicalFile != destination.canonicalFile) {
            source.copyTo(destination, overwrite = true)
        }
        logger.lifecycle("[mcv2] staged SM8650 NPU artifact for debug APK: ${destination.absolutePath}")
    }
}

val stageMcv2NpuW8a8Model by tasks.registering {
    group = "build setup"
    description = "Stages the SM8650 NPU MCV2 W8A8 artifact into debug APK assets (-Pmodels.mcv2.npu.w8a8=<file>), " +
        "verifying the cross-series calibrated artifact hash."
    doLast {
        val destination = mcv2NpuAssetDirectory.get().file(mcv2NpuW8a8AssetRelativePath).asFile
        val configuredPath = mcv2NpuW8a8ModelPath.orNull
        if (configuredPath == null) {
            if (destination.exists() && !destination.delete()) {
                throw GradleException("Could not remove stale MCV2 NPU W8A8 debug asset: ${destination.absolutePath}")
            }
            logger.lifecycle("[mcv2] no local NPU W8A8 artifact configured; removed any stale debug asset")
            return@doLast
        }

        val source = rootProject.file(configuredPath)
        if (!source.isFile || source.length() != mcv2NpuW8a8ArtifactBytes) {
            throw GradleException(
                "MCV2 NPU W8A8 artifact missing or wrong size " +
                    "(${if (source.isFile) "${source.length()} bytes" else "absent"}, expected $mcv2NpuW8a8ArtifactBytes): " +
                    source.absolutePath,
            )
        }
        val hash = sha256Hex(source)
        if (hash != mcv2NpuW8a8ArtifactSha256) {
            throw GradleException(
                "MCV2 NPU W8A8 artifact hash mismatch ($hash); expected the cross-series calibrated " +
                    "apply_plugin build $mcv2NpuW8a8ArtifactSha256",
            )
        }
        destination.parentFile.mkdirs()
        if (source.canonicalFile != destination.canonicalFile) {
            source.copyTo(destination, overwrite = true)
        }
        logger.lifecycle("[mcv2] staged SM8650 NPU W8A8 artifact for debug APK: ${destination.absolutePath}")
    }
}

val stageQuickSRNetNpuModel by tasks.registering {
    group = "build setup"
    description = "Stages the SM8650 QuickSRNetLarge upscaling artifact into debug APK assets (-Pmodels.upscaling.npu=<file>), " +
        "verifying the compiled hash."
    doLast {
        val destination = mcv2NpuAssetDirectory.get().file(upscalingNpuAssetRelativePath).asFile
        val configuredPath = upscalingNpuModelPath.orNull
        if (configuredPath == null) {
            if (destination.exists() && !destination.delete()) {
                throw GradleException("Could not remove stale QuickSRNet debug asset: ${destination.absolutePath}")
            }
            logger.lifecycle("[quicksrnet] no local QuickSRNet artifact configured; removed any stale debug asset")
            return@doLast
        }

        val source = rootProject.file(configuredPath)
        if (!source.isFile || source.length() != upscalingNpuArtifactBytes) {
            throw GradleException(
                "QuickSRNet artifact missing or wrong size " +
                    "(${if (source.isFile) "${source.length()} bytes" else "absent"}, expected $upscalingNpuArtifactBytes): " +
                    source.absolutePath,
            )
        }
        val hash = sha256Hex(source)
        if (hash != upscalingNpuArtifactSha256) {
            throw GradleException(
                "QuickSRNet artifact hash mismatch ($hash); expected the compiled " +
                    "apply_plugin build $upscalingNpuArtifactSha256",
            )
        }
        destination.parentFile.mkdirs()
        if (source.canonicalFile != destination.canonicalFile) {
            source.copyTo(destination, overwrite = true)
        }
        logger.lifecycle("[quicksrnet] staged SM8650 QuickSRNet artifact for debug APK: ${destination.absolutePath}")
    }
}

val stageMangaLightNpuModel by tasks.registering {
    group = "build setup"
    description = "Stages the SM8650 Manga Light 512×512 FP32 artifact into debug APK assets (-Pmodels.mangaLight.npu=<file>), " +
        "verifying the trial-compiled artifact hash."
    doLast {
        val destination = mcv2NpuAssetDirectory.get().file(mangaLightNpuAssetRelativePath).asFile
        val configuredPath = mangaLightNpuModelPath.orNull
        if (configuredPath == null) {
            if (destination.exists() && !destination.delete()) {
                throw GradleException("Could not remove stale Manga Light NPU debug asset: ${destination.absolutePath}")
            }
            logger.lifecycle("[mangaLight] no local Manga Light NPU artifact configured; removed any stale debug asset")
            return@doLast
        }

        val source = rootProject.file(configuredPath)
        if (!source.isFile || source.length() != mangaLightNpuArtifactBytes) {
            throw GradleException(
                "Manga Light NPU artifact missing or wrong size " +
                    "(${if (source.isFile) "${source.length()} bytes" else "absent"}, expected $mangaLightNpuArtifactBytes): " +
                    source.absolutePath,
            )
        }
        val hash = sha256Hex(source)
        if (hash != mangaLightNpuArtifactSha256) {
            throw GradleException(
                "Manga Light NPU artifact hash mismatch ($hash); expected the trial-compiled " +
                    "apply_plugin build $mangaLightNpuArtifactSha256",
            )
        }
        destination.parentFile.mkdirs()
        if (source.canonicalFile != destination.canonicalFile) {
            source.copyTo(destination, overwrite = true)
        }
        logger.lifecycle("[mangaLight] staged SM8650 Manga Light artifact for debug APK: ${destination.absolutePath}")
    }
}

val stageMangaLightHybridModels by tasks.registering {
    group = "build setup"
    doLast {
        val destination = mcv2NpuAssetDirectory.get().dir("models/coloring/manga-light-hybrid").asFile
        val artifacts = mapOf(
            "sam0.tflite" to (36_312_536L to "06a0436722c853e54c23a3b810a39f7bb47b77a33e74cbbd1763aa7cffe8253f"),
            "sam1.tflite" to (32_891_512L to "7da91f7f4d00abd4110e6da9583897d833d0c65f60e4e341baca74896c5bc7c7"),
            "sam2.tflite" to (23_498_360L to "cc0b844f39f6e5a1ca8faa6742102947ba904889cb725c19b49a9cb7ca2aeed0"),
            "sam3.tflite" to (52_386_032L to "2f9deffb67b09f4d0d328c287c01e31ddfe83b30c06daf03bb26a1b5147ec382"),
            "generator.tflite" to (179_058_976L to "49f13c8d8dd8f543ebed6a3b76ee60fcf3195680ec9acd93d4fe83e3cfcb72da"),
        )
        val directory = mangaLightHybridModelsDir.orNull
        if (directory == null) {
            artifacts.keys.forEach { name ->
                val file = File(destination, name)
                if (file.exists() && !file.delete()) throw GradleException("Cannot remove stale model: $file")
            }
            return@doLast
        }
        destination.mkdirs()
        artifacts.forEach { (name, expected) ->
            val source = File(rootProject.file(directory), name)
            if (!source.isFile || source.length() != expected.first || sha256Hex(source) != expected.second) {
                throw GradleException("Manga Light hybrid artifact is missing or invalid: $source")
            }
            val target = File(destination, name)
            if (source.canonicalFile != target.canonicalFile) source.copyTo(target, overwrite = true)
        }
    }
}

val stageMcv2NpuLibraries by tasks.registering {
    group = "build setup"
    description = "Stages the four Qualcomm dispatch libraries into debug jniLibs " +
        "(-Pmodels.mcv2.npu.libs=<dir>), verifying device-validated hashes."
    doLast {
        val arm64 = mcv2NpuJniLibsDirectory.get().dir("arm64-v8a").asFile
        val configuredPath = mcv2NpuLibraryDirectoryPath.orNull
        if (configuredPath == null) {
            if (arm64.exists() && !arm64.deleteRecursively()) {
                throw GradleException("Could not remove stale MCV2 NPU jniLibs: ${arm64.absolutePath}")
            }
            logger.lifecycle("[mcv2] no local QNN library directory configured; removed any stale debug jniLibs")
            return@doLast
        }

        val sourceDir = rootProject.file(configuredPath)
        arm64.mkdirs()
        for ((name, expectedSha256) in mcv2NpuLibraries) {
            val source = File(sourceDir, name)
            if (!source.isFile) {
                throw GradleException("Qualcomm dispatch library missing: ${source.absolutePath}")
            }
            val hash = sha256Hex(source)
            if (hash != expectedSha256) {
                throw GradleException(
                    "Qualcomm dispatch library hash mismatch for $name ($hash); " +
                        "expected the device-validated $expectedSha256",
                )
            }
            source.copyTo(File(arm64, name), overwrite = true)
        }
        logger.lifecycle("[mcv2] staged Qualcomm dispatch libraries for debug APK: ${arm64.absolutePath}")
    }
}

// TachiyomiAT: the guard runs on every assemble; enforcement only applies to
// NPU-staged debug variants, so ordinary builds (including release, where the
// ORT transit ships its own QNN libs) always pass.
androidComponents {
    onVariants { variant ->
        val capitalized = variant.name.replaceFirstChar { it.uppercaseChar() }
        val npuEnforced = mcv2NpuStagingEnabled && variant.buildType == "debug"
        val apkDirProvider = variant.artifacts.get(SingleArtifact.APK)
        val verifyMcv2NpuApkBytes = tasks.register("verify${capitalized}Mcv2NpuApkBytes") {
            inputs.dir(apkDirProvider)
            doLast {
                val apkDir = apkDirProvider.get().asFile
                val apks = apkDir
                    .listFiles { file: File -> file.isFile && file.name.endsWith(".apk") }
                    .orEmpty()
                    .sortedBy { it.name }
                val problems = mutableListOf<String>()
                var enforcedApks = 0
                for (apk in apks) {
                    ZipFile(apk).use { zip ->
                        if (npuEnforced) {
                            enforcedApks += 1
                            if (mcv2NpuModelPath.isPresent) {
                                verifyAsset(apk.name, zip, mcv2NpuAssetRelativePath, mcv2NpuArtifactSha256, problems)
                            }
                            if (mcv2NpuW8a8ModelPath.isPresent) {
                                verifyAsset(apk.name, zip, mcv2NpuW8a8AssetRelativePath, mcv2NpuW8a8ArtifactSha256, problems)
                            }
                            if (upscalingNpuModelPath.isPresent) {
                                verifyAsset(apk.name, zip, upscalingNpuAssetRelativePath, upscalingNpuArtifactSha256, problems)
                            }
                            if (mangaLightNpuModelPath.isPresent) {
                                verifyAsset(apk.name, zip, mangaLightNpuAssetRelativePath, mangaLightNpuArtifactSha256, problems)
                            }
                            val carriesArm64Libs = zip.entries().asSequence().any { it.name.startsWith("lib/arm64-v8a/") }
                            if (carriesArm64Libs) {
                                for ((name, expected) in qnnLibraryHashes) {
                                    val entry = zip.getEntry("lib/arm64-v8a/$name")
                                    if (entry == null) {
                                        problems += "${apk.name}: missing $name"
                                        continue
                                    }
                                    val hash = entrySha256(zip, entry)
                                    if (hash != expected) {
                                        problems += "${apk.name}: $name is $hash, expected device-tested $expected"
                                    }
                                }
                            }
                        } else {
                            if (zip.getEntry("assets/$mcv2NpuAssetRelativePath") != null) {
                                problems += "${apk.name}: stale staged NPU artifact present in a non-NPU build"
                            }
                            if (zip.getEntry("assets/$mcv2NpuW8a8AssetRelativePath") != null) {
                                problems += "${apk.name}: stale staged NPU W8A8 artifact present in a non-NPU build"
                            }
                            if (zip.getEntry("assets/$upscalingNpuAssetRelativePath") != null) {
                                problems += "${apk.name}: stale staged QuickSRNet artifact present in a non-NPU build"
                            }
                            if (zip.getEntry("assets/$mangaLightNpuAssetRelativePath") != null) {
                                problems += "${apk.name}: stale staged Manga Light artifact present in a non-NPU build"
                            }
                            for ((name, expected) in qnnLibraryHashes) {
                                val entry = zip.getEntry("lib/arm64-v8a/$name")
                                if (entry != null && entrySha256(zip, entry) == expected) {
                                    problems += "${apk.name}: stale staged $name bytes present in a non-NPU build"
                                }
                            }
                        }
                    }
                }
                if (problems.isNotEmpty()) {
                    throw GradleException(
                        "Packaged NPU content violates the device-tested contract:\n" +
                            problems.joinToString("\n"),
                    )
                }
                if (npuEnforced) {
                    logger.lifecycle(
                        "[mcv2] verified $enforcedApks APK(s): staged NPU artifact and QNN libraries match the device-tested hashes",
                    )
                } else {
                    logger.lifecycle("[mcv2] NPU staging not enabled; APK byte guard idle")
                }
            }
        }
        tasks.matching { it.name == "assemble$capitalized" }.configureEach {
            finalizedBy(verifyMcv2NpuApkBytes)
        }
    }
}

val fetchModels by tasks.registering {
    group = "build setup"
    description = "Provisions or verifies every model asset in scripts/models.manifest."
    doLast {
        val script = rootProject.file("scripts/fetch_models.py")
        val interpreters = if (org.gradle.internal.os.OperatingSystem.current().isWindows) {
            listOf(listOf("py", "-3"), listOf("python"), listOf("python3"))
        } else {
            listOf(listOf("python3"), listOf("python"))
        }
        val python = interpreters.firstOrNull { interpreter ->
            runCatching {
                exec {
                    commandLine(interpreter + "--version")
                    isIgnoreExitValue = true
                }
            }.isSuccess
        } ?: throw GradleException(
            "Model provisioning needs Python 3. Install it and the required converter packages, " +
                "then retry the APK build.",
        )
        exec {
            val verifyOnly = providers.gradleProperty("models.skip").orNull
                ?.let { it.isEmpty() || it.toBoolean() }
                ?: false
            commandLine(python + script.absolutePath + if (verifyOnly) listOf("--verify-only") else emptyList())
            workingDir(rootDir)
        }
    }
}

tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("DebugAssets")) {
        dependsOn(fetchModels, stageMcv2GpuModel, stageMcv2NpuModel, stageMcv2NpuW8a8Model, stageQuickSRNetNpuModel, stageMangaLightNpuModel, stageMangaLightHybridModels)
    } else if (name.startsWith("merge") && name.endsWith("DebugJniLibFolders")) {
        // Debug-only so release merges stop wiping the staged lib inputs (review finding F1).
        dependsOn(stageMcv2NpuLibraries)
    } else if (name.startsWith("merge") && name.endsWith("Assets")) {
        dependsOn(fetchModels)
    }
}

androidComponents {
    beforeVariants { variantBuilder ->
        // Disables standardBenchmark
        if (variantBuilder.buildType == "benchmark") {
            variantBuilder.enable = variantBuilder.productFlavors.containsAll(
                listOf("default" to "dev"),
            )
        }
    }
    onVariants(selector().withFlavor("default" to "standard")) {
        // Only excluding in standard flavor because this breaks
        // Layout Inspector's Compose tree
        it.packaging.resources.excludes.add("META-INF/*.version")
    }
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    if (project.hasProperty("runSyntheticJournalReplayBench")) {
        systemProperty("measurement.replayBench", "true")
    }
}

buildscript {
    dependencies {
        classpath(kotlinx.gradle)
    }
}
