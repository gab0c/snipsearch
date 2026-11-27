package com.snipsearch.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SearchFoundationIsolationTest {
    @Test
    fun newProviderFilesStayFreeOfTransportAndPlatformApis() {
        val files = MAIN_FILES.map { file("src/main/kotlin/com/snipsearch/search/$it") }
        for (source in files) {
            assertTrue(source.path, source.isFile)
            val text = source.readText()
            for (token in FORBIDDEN) {
                assertFalse("${source.name} contains $token", text.contains(token))
            }
        }
    }

    private fun file(relative: String): File {
        val cwd = File("").absoluteFile
        val candidates = listOf(cwd, cwd.resolve("app"), cwd.parentFile)
        val root = candidates.first { it.resolve("src/main/AndroidManifest.xml").isFile }
        return root.resolve(relative)
    }

    private companion object {
        val MAIN_FILES = listOf(
            "ProviderResult.kt",
            "SearchProviderConfig.kt",
            "ProviderEndpointPolicy.kt",
            "ProviderValidator.kt",
            "SearchQueryBuilder.kt",
            "BuiltInProviders.kt",
            "StrictJson.kt",
            "CustomProviderSchema.kt",
        )
        val FORBIDDEN = listOf(
            "HttpsURLConnection",
            "HttpURLConnection",
            "openConnection",
            "java.net.URL",
            "Socket",
            "InetAddress",
            "WebView",
            "loadUrl",
            "CookieManager",
            "FileOutputStream",
            "File(",
            "SharedPreferences",
            "DataStore",
            "JavascriptInterface",
            "android.",
            "Intent",
        )
    }
}
