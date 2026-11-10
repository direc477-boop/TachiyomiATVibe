package eu.kanade.tachiyomi.extension.util

import io.kotest.matchers.shouldBe
import kotlinx.serialization.decodeFromByteArray
import mihon.domain.extensionrepo.service.toExtensionRepo
import org.junit.jupiter.api.Test

class ExtensionLoaderCompatibilityTest {

    @Test
    fun `lib version bounds allow versions from 1_4 to 1_7`() {
        ExtensionLoader.LIB_VERSION_MIN shouldBe 1.4
        ExtensionLoader.LIB_VERSION_MAX shouldBe 1.7
    }

    @Test
    fun `version strings 1_4 through 1_7 are recognized as valid library versions`() {
        val versions = listOf("1.4.1", "1.4.10.1", "1.5.0", "1.5.28", "1.6.0", "1.6.4", "1.7.0", "1.7.1")
        for (version in versions) {
            val libVersion = version.split('.').take(2).joinToString(".").toDoubleOrNull()
            (libVersion != null && libVersion >= ExtensionLoader.LIB_VERSION_MIN && libVersion <= ExtensionLoader.LIB_VERSION_MAX) shouldBe true
        }
    }

    @Test
    fun `outdated version below 1_4 or future version above 1_7 are out of bounds`() {
        val invalidVersions = listOf("1.3.9", "1.8.0", "2.0.0")
        for (version in invalidVersions) {
            val libVersion = version.split('.').take(2).joinToString(".").toDoubleOrNull()
            (libVersion != null && libVersion >= ExtensionLoader.LIB_VERSION_MIN && libVersion <= ExtensionLoader.LIB_VERSION_MAX) shouldBe false
        }
    }

    @Test
    fun `extension title prefix stripping handles both Tachiyomi and Tachiyomix prefixes`() {
        val titles = listOf(
            "Tachiyomi: MangaDex" to "MangaDex",
            "Tachiyomix: Asura Scans" to "Asura Scans",
            "Simple Extension" to "Simple Extension",
        )

        for ((input, expected) in titles) {
            val stripped = input.substringAfter("Tachiyomi: ").substringAfter("Tachiyomix: ")
            stripped shouldBe expected
        }
    }

    @Test
    fun `repo url regex matches both index_json and index_min_json`() {
        val regex = """^https://.*/index(\.min)?\.json$""".toRegex()
        "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.json".matches(regex) shouldBe true
        "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json".matches(regex) shouldBe true
        "http://insecure.com/index.json".matches(regex) shouldBe false
        "https://domain.com/other.json".matches(regex) shouldBe false
    }

    @Test
    fun `repo dto parses json with missing shortName and extra fields`() {
        val json = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            isLenient = true
            coerceInputValues = true
        }
        val rawJson = """
            {
              "index_v2": "https://github.com/keiyoushi/extensions/raw/repo/index.pb",
              "meta": {
                "name": "Keiyoushi",
                "website": "https://keiyoushi.github.io",
                "signingKeyFingerprint": "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"
              }
            }
        """.trimIndent()

        val parsed = json.decodeFromString<mihon.domain.extensionrepo.service.ExtensionRepoMetaDto>(rawJson)
        val domainRepo = parsed.toExtensionRepo("https://raw.githubusercontent.com/keiyoushi/extensions/repo")
        domainRepo.name shouldBe "Keiyoushi"
        domainRepo.shortName shouldBe null
        domainRepo.signingKeyFingerprint shouldBe "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"
        parsed.indexV2 shouldBe "https://github.com/keiyoushi/extensions/raw/repo/index.pb"
    }

    @Test
    fun `proto extension store serialization roundtrip`() {
        val testStore = eu.kanade.tachiyomi.extension.api.ProtoExtensionStore(
            name = "TestRepo",
            extensionList = eu.kanade.tachiyomi.extension.api.ProtoExtensionList(
                extensions = listOf(
                    eu.kanade.tachiyomi.extension.api.ProtoExtension(
                        name = "Test Extension",
                        packageName = "eu.kanade.tachiyomi.extension.en.test",
                        versionName = "1.4.1",
                        versionCode = 1L,
                        extensionLib = "1.6",
                    ),
                ),
            ),
        )

        val bytes = kotlinx.serialization.protobuf.ProtoBuf.encodeToByteArray(
            eu.kanade.tachiyomi.extension.api.ProtoExtensionStore.serializer(),
            testStore,
        )
        val decoded = kotlinx.serialization.protobuf.ProtoBuf.decodeFromByteArray<eu.kanade.tachiyomi.extension.api.ProtoExtensionStore>(bytes)

        decoded.name shouldBe "TestRepo"
        decoded.extensionList?.extensions?.size shouldBe 1
        decoded.extensionList?.extensions?.first()?.packageName shouldBe "eu.kanade.tachiyomi.extension.en.test"
    }

    @Test
    fun `SManga and SChapter have setMemo and getMemo methods matching extension-api`() {
        val manga = eu.kanade.tachiyomi.source.model.SManga.create()
        val testMemo = kotlinx.serialization.json.buildJsonObject {
            put("testKey", kotlinx.serialization.json.JsonPrimitive("testValue"))
        }

        // Test direct setter and getter
        manga.memo = testMemo
        manga.memo shouldBe testMemo

        // Verify interface method setMemo exists via reflection (prevents NoSuchMethodError)
        val setMemoMethod = eu.kanade.tachiyomi.source.model.SManga::class.java.getMethod(
            "setMemo",
            kotlinx.serialization.json.JsonObject::class.java,
        )
        setMemoMethod.invoke(manga, testMemo)
        manga.memo shouldBe testMemo

        val chapter = eu.kanade.tachiyomi.source.model.SChapter.create()
        chapter.memo = testMemo
        chapter.memo shouldBe testMemo

        val chapterSetMemoMethod = eu.kanade.tachiyomi.source.model.SChapter::class.java.getMethod(
            "setMemo",
            kotlinx.serialization.json.JsonObject::class.java,
        )
        chapterSetMemoMethod.invoke(chapter, testMemo)
        chapter.memo shouldBe testMemo
    }

    @Test
    fun `okhttp3 CompressionInterceptor and algorithms resolve for modern extensions`() {
        val interceptorClass = Class.forName("okhttp3.CompressionInterceptor")
        interceptorClass shouldBe okhttp3.CompressionInterceptor::class.java

        val gzipClass = Class.forName("okhttp3.Gzip")
        gzipClass shouldBe okhttp3.Gzip::class.java

        val brotliClass = Class.forName("okhttp3.brotli.Brotli")
        brotliClass shouldBe okhttp3.brotli.Brotli::class.java

        val interceptor = okhttp3.CompressionInterceptor(
            okhttp3.Gzip,
            okhttp3.brotli.Brotli,
        )
        val client = okhttp3.OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()

        client.interceptors.contains(interceptor) shouldBe true
    }

    @Test
    fun `com squareup zstd okio OkioZstd methods resolve for modern extensions`() {
        val okioZstdClass = Class.forName("com.squareup.zstd.okio.OkioZstd")
        val decompressMethod = okioZstdClass.getMethod("zstdDecompress", okio.Source::class.java)
        decompressMethod.returnType shouldBe okio.Source::class.java

        val compressMethod = okioZstdClass.getMethod("zstdCompress", okio.Sink::class.java)
        compressMethod.returnType shouldBe okio.Sink::class.java
    }
}
