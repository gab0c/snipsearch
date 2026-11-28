package com.snipsearch.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderEndpointPolicyTest {
    @Test
    fun acceptsPublicHttpsEndpoints() {
        for (url in listOf(
            "https://www.google.com/search",
            "https://example.com",
            "https://example.com:443/x",
            "https://xn--bcher-kva.example/",
            "https://a.b.example.org/p/q?x=1&y",
        )) {
            assertNull(url, ProviderEndpointPolicy.check(url, "url", allowQuery = true))
        }
    }

    @Test
    fun rejectsUnsafeSchemes() {
        for (url in listOf(
            "http://example.com/",
            "javascript:alert(1)",
            "data:text/html,x",
            "file:///etc/hosts",
            "content://x/y",
            "intent://x#Intent;end",
            "ftp://example.com/",
            "mailto:a@example.com",
        )) {
            assertCode(url, ProviderConfigErrorCode.UNSAFE_SCHEME)
        }
    }

    @Test
    fun rejectsNonCanonicalUrls() {
        for (url in listOf(
            "HTTPS://example.com/",
            "https://Example.com/",
            "https://example.com./",
            "https://example.com/a/../b",
            "https://example.com/./b",
            "https://example.com/%2e/b",
            "https://example.com/.%2E/b",
            "https://example.com/%2E%2e/b",
            "https://example.com/%2e./b",
            "https://example.com//b",
        )) {
            assertCode(url, ProviderConfigErrorCode.NON_CANONICAL_URL)
        }
    }

    @Test
    fun rejectsUserInfo() {
        assertCode("https://user@example.com/", ProviderConfigErrorCode.USERINFO_NOT_ALLOWED)
        assertCode("https://user:pw@example.com/", ProviderConfigErrorCode.USERINFO_NOT_ALLOWED)
    }

    @Test
    fun rejectsNonStandardPorts() {
        assertCode("https://example.com:8443/", ProviderConfigErrorCode.PORT_NOT_ALLOWED)
        assertCode("https://example.com:80/", ProviderConfigErrorCode.PORT_NOT_ALLOWED)
        assertCode("https://example.com:0/", ProviderConfigErrorCode.PORT_NOT_ALLOWED)
    }

    @Test
    fun rejectsIpLiterals() {
        for (url in listOf(
            "https://192.168.1.2/",
            "https://8.8.8.8/",
            "https://[::1]/",
            "https://[2001:db8::1]/",
            "https://2130706433/",
            "https://0x7f000001/",
        )) {
            assertCode(url, ProviderConfigErrorCode.IP_LITERAL_NOT_ALLOWED)
        }
    }

    @Test
    fun rejectsLocalNetworkHosts() {
        for (url in listOf(
            "https://localhost/",
            "https://intranet/",
            "https://printer.local/",
            "https://router.lan/",
            "https://nas.home.arpa/",
            "https://home.arpa/",
            "https://svc.internal/",
            "https://app.localhost/",
        )) {
            assertCode(url, ProviderConfigErrorCode.LOCAL_NETWORK_NOT_SUPPORTED)
        }
    }

    @Test
    fun rejectsInvalidHosts() {
        assertCode("https://exa_mple.com/", ProviderConfigErrorCode.INVALID_HOST)
        assertCode("https://-bad.example.com/", ProviderConfigErrorCode.INVALID_HOST)
        assertCode("https://example.com%2eevil.com/", ProviderConfigErrorCode.INVALID_HOST)
    }

    @Test
    fun rejectsMissingHosts() {
        assertCode("https:///path", ProviderConfigErrorCode.MISSING_HOST)
        assertCode("https://:443/path", ProviderConfigErrorCode.MISSING_HOST)
    }

    @Test
    fun rejectsMalformedUrls() {
        for (url in listOf(
            "https://exa mple.com/",
            "https://example.com\\evil.com/",
            "https://example.com:/",
            "https://example.com/?",
            "https://example.com/?=x",
            "https:example.com",
        )) {
            assertCode(url, ProviderConfigErrorCode.MALFORMED_URL)
        }
    }

    @Test
    fun rejectsNonAsciiUrls() {
        assertCode("https://bücher.example/", ProviderConfigErrorCode.NON_ASCII_URL)
    }

    @Test
    fun rejectsTooLongUrls() {
        assertCode("https://example.com/" + "a".repeat(2048), ProviderConfigErrorCode.URL_TOO_LONG)
    }

    @Test
    fun rejectsFragments() {
        assertCode("https://example.com/#x", ProviderConfigErrorCode.FRAGMENT_NOT_ALLOWED)
        assertCode("https://example.com/#", ProviderConfigErrorCode.FRAGMENT_NOT_ALLOWED)
    }

    @Test
    fun rejectsQueriesWhenDisabled() {
        assertCode("https://example.com/v1?x=1", ProviderConfigErrorCode.QUERY_NOT_ALLOWED, allowQuery = false)
    }

    @Test
    fun rejectsCredentialQueryParameters() {
        for (url in listOf(
            "https://example.com/?api_key=1",
            "https://example.com/?access_token=1",
            "https://example.com/?X-Api-Key=1",
            "https://example.com/?password=1",
            "https://example.com/?sig=1",
        )) {
            assertCode(url, ProviderConfigErrorCode.PLAINTEXT_CREDENTIAL)
        }
    }

    @Test
    fun ordinaryParameterNamesAreNotSensitive() {
        for (name in listOf("q", "query", "text", "iss", "view", "rpt", "encoded_image", "upfile", "imageBin", "keyword", "ref")) {
            assertFalse(name, ProviderEndpointPolicy.isSensitiveName(name))
        }
    }

    @Test
    fun originUsesTheCheckedHost() {
        assertEquals("https://lens.google.com", ProviderEndpointPolicy.originOf("https://lens.google.com/v3/upload"))
        assertEquals("https://example.com", ProviderEndpointPolicy.originOf("https://example.com:443/x"))
        assertNull(ProviderEndpointPolicy.originOf("http://x.example/"))
    }

    private fun assertCode(url: String, code: ProviderConfigErrorCode, allowQuery: Boolean = true) {
        val error = ProviderEndpointPolicy.check(url, "url", allowQuery)
        assertEquals(url, code, error?.code)
        assertEquals(url, "url", error?.field)
    }
}
