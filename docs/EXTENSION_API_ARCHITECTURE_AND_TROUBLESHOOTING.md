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

## 6. Modern OkHttp 5 & Extension Classloading

Modern extensions (e.g., compiled against `tachiyomix:1.6+` or Keiyoushi's latest generator) make direct use of OkHttp 5 features:
1. **`okhttp3.CompressionInterceptor` and Algorithm Objects**:
   - Extensions configure their custom `getClient()` lazy instance with `okhttp3.CompressionInterceptor(okhttp3.Gzip, okhttp3.brotli.Brotli, ...)`.
   - The host app bundles these implementations in `core:common` (`okhttp3/CompressionInterceptor.kt`, `Gzip.kt`, `brotli/Brotli.kt`, `Deflate.kt`, `Zstd.kt`).
   - When `ChildFirstPathClassLoader` fails to find the class in the extension APK, it resolves it from the host app DEX.
2. **Defensive Error Handling for Coroutines**:
   - Extension invocations (`getFilterList()`, `getSearchManga()`, `getPopularManga()`) can throw `LinkageError` (e.g. `NoClassDefFoundError`, `NoSuchMethodError`).
   - Because `LinkageError` extends `java.lang.Error` (not `java.lang.Exception`), UI coroutine callers must catch `CancellationException` first (to rethrow) and `Throwable` second to prevent crashing the entire process.

