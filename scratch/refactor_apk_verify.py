path = "app/build.gradle.kts"
with open(path, "r", encoding="utf-8") as f:
    content = f.read()

# 1. Replace the entire VerifyMcv2NpuApkBytesTask class declaration with helper functions and qnnLibraryHashes map
old_class_start = "// TachiyomiAT: final-APK guard for the experimental NPU route. Enforcement is\n// scoped to NPU-staged debug variants; every other build passes through unless\n// stale NPU content leaked in, and ORT-transit QNN libs are left untouched.\nabstract class VerifyMcv2NpuApkBytesTask : DefaultTask() {"

old_class_end = """    companion object {
        val QNN_LIBRARY_HASHES = linkedMapOf(
            "libQnnHtp.so" to "09e930b6af975bae23bcf6965ac2ce60f6b999aff37cb7eabe9b4d7c1bc3f264",
            "libQnnSystem.so" to "b30bd9880dc48f6c5349185a7d45b326f8a064b10072e60094354fa23d9a2027",
            "libQnnHtpV75Stub.so" to "8be2bf0b0c6839d3e3bf9d8435e9b92d3fde0ccdea343d89960d75285ad28dab",
            "libQnnHtpV75Skel.so" to "2f6cfe5aae553d8ec88b6741bf8598a557375eacfc58056c2667fc43fda64f6a",
        )
    }
}"""

replacement = """val qnnLibraryHashes = linkedMapOf(
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
}"""

assert old_class_start in content, "old_class_start not found"
assert old_class_end in content, "old_class_end not found"

start_idx = content.find(old_class_start)
end_idx = content.find(old_class_end) + len(old_class_end)

content = content[:start_idx] + replacement + content[end_idx:]

# 2. Update val mcv2NpuLibraries reference
mcv2_lib_needle = "val mcv2NpuLibraries = VerifyMcv2NpuApkBytesTask.QNN_LIBRARY_HASHES"
new_mcv2_lib = "val mcv2NpuLibraries = qnnLibraryHashes"
assert mcv2_lib_needle in content, "mcv2_lib_needle not found"
content = content.replace(mcv2_lib_needle, new_mcv2_lib, 1)

# 3. Update androidComponents registration
old_reg = """        val verifyMcv2NpuApkBytes = tasks.register(
            "verify${capitalized}Mcv2NpuApkBytes",
            VerifyMcv2NpuApkBytesTask::class,
        ) {
            apkFolder.set(variant.artifacts.get(SingleArtifact.APK))
            this.enforce.set(npuEnforced)
            this.verifyFp32.set(mcv2NpuModelPath.isPresent)
            this.verifyW8a8.set(mcv2NpuW8a8ModelPath.isPresent)
            this.verifyQuickSRNet.set(upscalingNpuModelPath.isPresent)
        }"""

new_reg = """        val apkDirProvider = variant.artifacts.get(SingleArtifact.APK)
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
                        "Packaged NPU content violates the device-tested contract:\\n" +
                            problems.joinToString("\\n"),
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
        }"""
assert old_reg in content, "old_reg not found"
content = content.replace(old_reg, new_reg, 1)

with open(path, "w", encoding="utf-8") as f:
    f.write(content)

print("Refactored APK verify tasks in build.gradle.kts")
