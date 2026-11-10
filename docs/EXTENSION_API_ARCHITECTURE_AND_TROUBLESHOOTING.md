# Extension Subsystem Architecture & Troubleshooting Guide

This guide documents the architecture, repository indexing protocol, and troubleshooting steps for the extension subsystem in TachiyomiATVibe. Refer to this document whenever the Extensions list appears empty, repository sync fails, or upstream Mihon makes breaking changes to extension indexing.

---

## 1. Quick Diagnostic Matrix

| Symptom | Probable Cause | Files to Inspect | Quick Resolution |
| :--- | :--- | :--- | :--- |
| **Extensions tab is blank** or shows 0 extensions with no filter applied. | Repository moved to a new indexing format (`index.pb`), or index is GZIP-compressed and failing to decode. | `ExtensionApi.kt` | Verify `index.pb` network fetch, check for GZIP magic bytes (`0x1F, 0x8B`), ensure `ProtoBuf.decodeFromByteArray` succeeds. |
| **Only dummy/stub extensions show** (e.g. "Update to Mihon 0.20.1+"). | Modern endpoints failed and app fell back to deprecated `index.min.json`. | `ExtensionApi.kt` | Check logcat for `index.pb` or `index.json` HTTP errors (e.g., 404, redirect, SSL, format mismatch). |
| **Extensions install but do not show in Sources** tab. | `libVersion` mismatch or Bundle meta-data float parsing failure. | `ExtensionLoader.kt` | Ensure `appInfo.metaData?.getFloat(METADATA_LIB_VERSION)` fallback is active and within `LIB_VERSION_MIN`..`LIB_VERSION_MAX`. |
| **Extension installation fails** or hangs at "Installing". | Android `PackageInstaller` session error or scoped storage access permission issue. | `PackageInstallerInstaller.kt`, `ExtensionInstaller.kt` | Check `logcat -s PackageInstallerInstaller` for `EXTRA_STATUS` code and message. |
| **Crash with `NoSuchMethodError: setMemo`** when searching or browsing sources. | Extension compiled against `tachiyomix:1.6` expects `SManga.setMemo` / `SChapter.setMemo` (`JsonObject`). | `SManga.kt`, `SMangaImpl.kt`, `SChapter.kt`, `SChapterImpl.kt`, `ChapterImpl.kt` | Implement `var memo: JsonObject` with default `JsonObject(emptyMap())` on `SManga` and `SChapter`. |
| **Crash with `NoClassDefFoundError: Lokhttp3/CompressionInterceptor;`** when initializing extension client. | Modern extensions instantiate OkHttp 5 compression API (`CompressionInterceptor`, `Gzip`, `Brotli`, `Deflate`, `Zstd`). | `core/common/src/main/kotlin/okhttp3/` | Host app provides modern `okhttp3.CompressionInterceptor` and algorithms in `core:common`, loaded via `ChildFirstPathClassLoader` fallback. |
| **`JsonDecodingException: Unexpected JSON token at offset 0 ... had '(' instead`** (binary input). | Cloudflare / server compressed JSON with Zstandard (`0x28` = `'('`), but `zstd` decompression was skipped or stubbed. | `core/common/src/main/kotlin/okhttp3/zstd/Zstd.kt`, `core/common/build.gradle.kts` | Ensure `com.github.luben:zstd-jni:1.5.6-8@aar` is bundled and `Zstd.decompress()` uses `ZstdInputStream`. |
| **Crash with `NoClassDefFoundError: Lcom/squareup/zstd/okio/OkioZstd;`** during search or fetch. | Extensions compiled with Square's `zstd-kmp-okio` invoke extension functions `Source.zstdDecompress()` and `Sink.zstdCompress()`. | `core/common/src/main/kotlin/com/squareup/zstd/okio/OkioZstd.kt` | Provide JVM static bridge class `com.squareup.zstd.okio.OkioZstd` wrapping `ZstdInputStream` / `ZstdOutputStream`. |
| **Crash with `AbstractMethodError: ... chapterListParse(Response)` or `mangaDetailsParse(Response)`**. | Modern extensions (`tachiyomix:1.6+`) implement coroutine `getMangaUpdate` instead of old RxJava abstract parsing methods. | `source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/online/HttpSource.kt`, `SMangaUpdate.kt` | Provide `SMangaUpdate` model, route `getMangaDetails` and `getChapterList` to `getMangaUpdate`, and declare RxJava parse methods as `open` with fallback stubs. |
| **Error `getMangaUpdate must not be called concurrently for same manga`**. | Opening manga details triggers parallel `fetchMangaFromSource` and `fetchChaptersFromSource` calling `getMangaUpdate` simultaneously on the same manga URL. | `app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModel.kt`, `HttpSource.kt` | Serialize fetches sequentially in `MangaScreenModel`, and wrap `HttpSource.getMangaUpdate` in a URL-keyed `Mutex`. |
| **Crash or failure with `IgnoreGzipInterceptor must not be present in default client`**. | Modern extensions require default `OkHttpClient` to not bundle `IgnoreGzipInterceptor` or `BrotliInterceptor`. | `NetworkHelper.kt` | Remove `IgnoreGzipInterceptor()` and `BrotliInterceptor` from `NetworkHelper.client`. |

---

## 2. Repository Protocol & Indexing Hierarchy

Upstream extension repositories (e.g., Keiyoushi) serve extension catalogs over HTTP. TachiyomiATVibe queries repository endpoints in a prioritized fallback cascade:

```
                  +-----------------------------------+
                  | Fetch repository base URL         |
                  +-----------------+-----------------+
                                    |
            1. Try Protobuf         v
            +---------------------------------------+
            | GET /index.pb (GZIP-compressed proto) |
            +-------------------+-------------------+
                                | Success: decode via ProtoExtensionStore
                                | Failure (HTTP error / parsing)
            2. Try JSON         v
            +---------------------------------------+
            | GET /index.json (Modern JSON schema)  |
            +-------------------+-------------------+
                                | Success: decode RepoIndexJsonObject / List
                                | Failure
            3. Fallback         v
            +---------------------------------------+
            | GET /index.min.json (Legacy schema)   |
            +---------------------------------------+
```

### Key Protocol Invariants:
1. **GZIP Compression:** `index.pb` and `index.json` may be transmitted as raw GZIP streams without `Content-Encoding: gzip` headers. Every byte array must pass through `decompressIfGzipped()` before decoding. GZIP streams begin with magic bytes `0x1F, 0x8B`.
2. **UTF-8 BOM:** JSON responses from certain Git CDNs may include a Byte Order Mark (`\uFEFF`). Always trim and strip the prefix before JSON deserialization.
3. **Dummy Extension Filtering:** Repositories undergoing deprecation serve placeholder warning packages (e.g., `18+ Deprecated`, `Update to Mihon`). `ExtensionApi.isDummyWarningExtension()` drops these from the available list.
4. **Multi-Language Normalization:** If an extension contains multiple languages, its listing language is normalized to `"all"` to prevent the app's default language filter from hiding it.

---

## 3. Core Components & Responsibilities

### `ExtensionApi.kt`
* **Path:** `app/src/main/java/eu/kanade/tachiyomi/extension/api/ExtensionApi.kt`
* **Role:** Network fetcher, decompressor, and deserializer for repository catalogs.
* **Key Schemas:**
  * `ProtoExtensionStore`: Protobuf container representing the repository root.
  * `ProtoExtensionList`: List wrapper containing extension entries.
  * `ProtoExtension`: Extension item containing `pkg_name`, `version_name`, `version_code`, `lib_version`, `sources`, and `apk_name`.
  * `RepoIndexJsonObject`: Modern JSON structure containing `extensionList`.
* **Helper:** `decompressIfGzipped(bytes: ByteArray)` detects magic header `[0x1F, 0x8B]` and wraps payloads with `GZIPInputStream`.

### `ExtensionLoader.kt`
* **Path:** `app/src/main/java/eu/kanade/tachiyomi/extension/util/ExtensionLoader.kt`
* **Role:** Inspects installed APKs, parses manifest metadata, and validates library version boundaries (`LIB_VERSION_MIN` to `LIB_VERSION_MAX`).
* **Android Metadata Quirk:** In Android 11+, the manifest value `<meta-data android:name="tachiyomix.extensionLib" android:value="1.6" />` is returned as a `Float` by the framework. If `getString` returns null, `metaData.getFloat(METADATA_LIB_VERSION)` is checked.
* **Source Extraction:** Reads all source languages using `sources.map { it.lang }.toSet()` rather than restricting to `CatalogueSource`.

### `ExtensionRepoDto.kt`
* **Path:** `domain/src/main/java/mihon/domain/extensionrepo/service/ExtensionRepoDto.kt`
* **Role:** Data transfer objects for `repo.json` metadata, including the `index_v2` URL pointer used by modern repositories.

### `PackageInstallerInstaller.kt`
* **Path:** `app/src/main/java/eu/kanade/tachiyomi/extension/installer/PackageInstallerInstaller.kt`
* **Role:** Handles Android `PackageInstaller` session lifecycle and provides diagnostic error reporting if installation is rejected by the OS.

---

## 4. Triage & Diagnostic Runbook (2-Minute Workflow)

When extension loading fails, execute the following steps in sequence:

### Step 1: Verify Upstream Repository Endpoints via CLI
```bash
# Check if index.pb is reachable and inspect HTTP status
curl -sI "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb"

# Check if index.pb starts with GZIP magic bytes (1f 8b)
curl -sL "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb" | xxd -l 8
# Expected output begins with: 1f8b 08...
```

### Step 2: Run Unit Tests
Verify that serialization and schema contracts are intact:
```bash
./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.tachiyomi.extension.util.ExtensionLoaderCompatibilityTest"
```

### Step 3: Inspect Android Device Logcat
Filter device logs for extension indexing and installation events:
```bash
adb logcat -s ExtensionApi:D ExtensionLoader:D PackageInstallerInstaller:E
```
* Look for: `"Failed to get index.pb from <url>"`
* Look for: `"PackageInstaller error: status=<status>, message=<message>"`

---

## 5. Adding New Repository Schemas

If upstream Mihon introduces a new format (e.g., `index_v3` or FlatBuffers):
1. Define the DTO in `ExtensionApi.kt` using `@Serializable`.
2. Insert the new endpoint attempt at the top of `ExtensionApi.findExtensions()`.
3. Wrap raw bytes with `decompressIfGzipped()`.
4. Add round-trip unit test cases in `ExtensionLoaderCompatibilityTest.kt`.
5. Maintain legacy fallbacks (`index.pb` -> `index.json` -> `index.min.json`) so older third-party repos do not break.

---

## 6. Modern OkHttp 5 & Compression Pipeline

Modern extensions (compiled against `tachiyomix:1.6+` or Keiyoushi's latest pipeline) make direct use of OkHttp 5 features and modern compression:

### 6.1 `okhttp3.CompressionInterceptor` and Algorithm Objects
* **Background:** Extensions configure their custom `getClient()` lazy instance with `okhttp3.CompressionInterceptor(okhttp3.Gzip, okhttp3.brotli.Brotli, okhttp3.Deflate, okhttp3.zstd.Zstd)`.
* **Host App Architecture:** The host app bundles these implementations in `core:common` (`okhttp3/CompressionInterceptor.kt`, `Gzip.kt`, `brotli/Brotli.kt`, `Deflate.kt`, `Zstd.kt`).
* **ClassLoader Resolution:** When an extension is loaded in a sandboxed `ChildFirstPathClassLoader`, any classes omitted from the extension APK resolve automatically from the host app DEX via classloader parent delegation.
* **Network Helper Invariant:** Extensions explicitly validate that `IgnoreGzipInterceptor` and `BrotliInterceptor` are **NOT** present in the base `OkHttpClient`. In `NetworkHelper.kt`, the default client must leave compression handling to `CompressionInterceptor`.

### 6.2 Square Okio Zstandard Bridge (`com.squareup.zstd.okio.OkioZstd`)
* **Symptom:** Fatal crash on search/fetch with:
  `java.lang.NoClassDefFoundError: Failed resolution of: Lcom/squareup/zstd/okio/OkioZstd;`
* **Root Cause:** Certain extensions (e.g., `allporncomicsco`, `mangageko`) are built with Square's multiplatform `zstd-kmp-okio` (`com.squareup.zstd.okio.OkioZstdKt`), which calls `Source.zstdDecompress()` and `Sink.zstdCompress()`. The extension APK does not bundle this dependency.
* **Implementation:** `core/common/src/main/kotlin/com/squareup/zstd/okio/OkioZstd.kt` provides the exact package and class signature:
  ```kotlin
  @file:JvmName("OkioZstd")
  package com.squareup.zstd.okio

  import com.github.luben.zstd.ZstdInputStream
  import com.github.luben.zstd.ZstdOutputStream
  import okio.Sink
  import okio.Source
  import okio.buffer
  import okio.sink
  import okio.source

  fun Source.zstdDecompress(): Source = ZstdInputStream(buffer().inputStream()).source()
  fun Sink.zstdCompress(): Sink = ZstdOutputStream(buffer().outputStream()).sink()
  ```
* **Native Backend:** Decompression is backed by `com.github.luben:zstd-jni:1.5.6-8@aar` via JNI across all supported ABIs (`arm64-v8a`, `armeabi-v7a`, `x86_64`).

---

## 7. Extension-lib 1.6 (`tachiyomix:1.6+`) Contract & Migration

Extensions compiled with `tachiyomix:1.6+` depart significantly from legacy 1.2–1.4 extension APIs. The host app provides backwards and forwards compatibility bridges:

### 7.1 `getMangaUpdate` vs. Legacy Abstract Parsing
* **Symptom:** Non-fatal or swallowed crash during global search or manga details fetch:
  `java.lang.AbstractMethodError: abstract method "eu.kanade.tachiyomi.source.model.SManga eu.kanade.tachiyomi.source.online.HttpSource.mangaDetailsParse(okhttp3.Response)" on receiver java.lang.Class<keiyoushi.source.Generated>`
* **Root Cause:** Modern sources override coroutine-based `getMangaUpdate(SManga, Boolean): SMangaUpdate`. They do **not** override legacy RxJava `mangaDetailsParse(Response)` or `chapterListParse(Response)`. If `HttpSource` declares those methods `abstract`, ART throws `AbstractMethodError` at runtime.
* **Architecture Fix:**
  1. `SMangaUpdate` data class is defined in `source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/model/SMangaUpdate.kt`:
     ```kotlin
     data class SMangaUpdate(
         val manga: SManga,
         val chapters: List<SChapter>,
     )
     ```
  2. In `eu.kanade.tachiyomi.source.Source`, `getMangaUpdate` is declared with default fallback delegating to `getMangaDetails` and `getChapterList`.
  3. In `HttpSource.kt`, `mangaDetailsParse` and `chapterListParse` are declared `open` (not `abstract`) with safe empty fallback implementations.
  4. In `HttpSource.kt`, `getMangaDetails` and `getChapterList` check and delegate to `getMangaUpdate`:
     ```kotlin
     override suspend fun getMangaDetails(manga: SManga): SManga =
         getMangaUpdate(manga, needsDetails = true).manga

     override suspend fun getChapterList(manga: SManga): List<SChapter> =
         getMangaUpdate(manga, needsDetails = false).chapters
     ```

### 7.2 Concurrency & Mutex Protection
* **Symptom:** Extension throws `IllegalStateException: getMangaUpdate must not be called concurrently for same manga`.
* **Root Cause:** When opening manga details, `MangaScreenModel` traditionally triggered `fetchMangaFromSource()` and `fetchChaptersFromSource()` in parallel via `async`/`launch`. Because both call `getMangaUpdate` on the same manga instance, the extension's internal concurrency assertion failed.
* **Resolution:**
  1. **Serialized Execution:** `MangaScreenModel.kt` executes manga details and chapter list sequentially: `fetchMangaFromSource(manualFetch = true)` is awaited before initiating chapter fetches.
  2. **URL-Keyed Mutex:** `HttpSource.kt` maintains a per-manga `ConcurrentHashMap<String, Mutex>()`. Calls to `getMangaUpdate` acquire the lock for that manga's `url`, guaranteeing that parallel requests queue safely instead of aborting.

### 7.3 `SManga.memo` and `SChapter.memo` (`JsonObject`)
* **Symptom:** Crash on search or browsing with `NoSuchMethodError: setMemo` or `getMemo`.
* **Root Cause:** Modern extensions attach structured JSON metadata memos to `SManga` and `SChapter`.
* **Resolution:**
  - Added `var memo: kotlinx.serialization.json.JsonObject` to `SManga.kt` and `SChapter.kt`.
  - Implemented with default empty `JsonObject(emptyMap())` in `SMangaImpl.kt`, `SChapterImpl.kt`, and `domain/ChapterImpl.kt`.

---

## 8. Defensive LinkageError Isolation in UI & Coroutines

Extensions execute untrusted third-party bytecode inside the host process. When an extension references a missing class or method, the Android runtime throws `java.lang.LinkageError` (e.g. `NoClassDefFoundError`, `NoSuchMethodError`, `AbstractMethodError`).

* **CRITICAL:** `LinkageError` extends `java.lang.Error`, **not** `java.lang.Exception`. Standard `try { ... } catch (e: Exception)` blocks will **not** catch it.
* **Coroutines Rule:** Always rethrow `CancellationException` first, then catch `Throwable` to insulate the app process:
  ```kotlin
  try {
      source.getSearchManga(page, query, filters)
  } catch (e: CancellationException) {
      throw e
  } catch (e: Throwable) {
      logcat(LogPriority.ERROR, e) { "Source ${source.name} failed during search" }
      emptyList()
  }
  ```
* This isolation prevents a single malfunctioning or outdated extension from crashing global search, browsing, or library updates.

---

## 9. Native & Hardware Acceleration Staging (Qualcomm QNN / LiteRT)

When testing or running features that interact with native runtimes (e.g., MCV2 NPU colorization, LiteRT GPU/NPU delegates, ONNX Runtime):
1. **Qualcomm QNN Dispatch Libraries:** Fast debug builds must include `libQnnHtp.so`, `libQnnHtpV75Skel.so`, `libQnnHtpV75Stub.so`, and `libQnnSystem.so` (version 2.28+ / System API >= 1.14).
2. **Auto-Staging in Gradle:** `app/build.gradle.kts` automatically defaults `models.mcv2.npu.libs` to `models_local/qnn-libs` when the directory is present.
3. **Runtime Prechecks:** Colorization engines perform `dispatchPrecheck()` before calling native LiteRT delegates. If an outdated 2.68 MB `libQnnSystem.so` from third-party AARs is detected, execution fails gracefully with an actionable error rather than triggering a native `SIGABRT`.
4. **On-Disk Model Fallbacks:** When builds are generated with `-Pmodels.exclude=true`, `NpuMangaColorizationEngine` and `LiteRTMangaInpaintingEngine` check `context.noBackupFilesDir` for existing on-device cached models before failing.

