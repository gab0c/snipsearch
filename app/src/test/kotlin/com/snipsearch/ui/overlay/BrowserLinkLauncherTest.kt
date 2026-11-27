package com.snipsearch.ui.overlay

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.webkit.WebResourceRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class BrowserLinkLauncherTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val clicked = Uri.parse("https://example.org/article")

    @Test fun customTabTargetsDefaultBrowserWithNullSessionAndNoReferrerOrHeaders() {
        val probe = Intent(Intent.ACTION_VIEW, BrowserLinkLauncher.browserProbe).addCategory(Intent.CATEGORY_BROWSABLE)
        val resolved = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "example.browser"; name = "example.browser.Main"; exported = true
                applicationInfo = ApplicationInfo().apply { packageName = "example.browser" }
            }
        }
        shadowOf(app.packageManager).addResolveInfoForIntent(probe, listOf(resolved))
        val intent = requireNotNull(BrowserLinkLauncher.intent(app, clicked))
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(clicked, intent.data)
        assertEquals("example.browser", intent.`package`)
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(intent.hasExtra(BrowserLinkLauncher.SESSION))
        assertNull(intent.extras?.getBinder(BrowserLinkLauncher.SESSION))
        assertEquals(setOf(BrowserLinkLauncher.SESSION), intent.extras?.keySet())
        assertNull(BrowserLinkLauncher.intent(app, Uri.parse("intent://bad")))
    }

    @Test fun unavailableCustomTabFallsBackToRegularBrowserIntent() {
        val launches = mutableListOf<Intent>()
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) {
                launches += Intent(intent)
                if (launches.size == 1) throw ActivityNotFoundException()
            }
        }
        assertTrue(BrowserLinkLauncher.open(context, clicked))
        assertEquals(2, launches.size)
        assertTrue(launches[0].hasExtra(BrowserLinkLauncher.SESSION))
        assertFalse(launches[1].hasExtra(BrowserLinkLauncher.SESSION))
        assertEquals(clicked, launches[1].data)
        assertEquals(launches[0].`package`, launches[1].`package`)
    }

    @Test fun onlyExplicitMainFrameLinkTapsLeaveEmbeddedPane() {
        val launches = mutableListOf<Intent>()
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) { launches += intent }
        }
        val session = TextSearchSession(context, online = { true })
        session.warm()
        val web = requireNotNull(session.webView)
        val client = shadowOf(web).webViewClient
        assertTrue(client.shouldOverrideUrlLoading(web, request()))
        assertTrue(launches.isEmpty())
        session.submit("selected text")
        assertFalse(client.shouldOverrideUrlLoading(web, request(gesture = false)))
        assertFalse(client.shouldOverrideUrlLoading(web, request(redirect = true)))
        assertFalse(client.shouldOverrideUrlLoading(web, request(mainFrame = false)))
        assertFalse(client.shouldOverrideUrlLoading(web, request(method = "POST")))
        assertTrue(client.shouldOverrideUrlLoading(web, request(gesture = false, uri = clicked)))
        assertNotNull(client.shouldInterceptRequest(web, request(gesture = false, uri = clicked)))
        assertTrue(launches.isEmpty())
        assertTrue(client.shouldOverrideUrlLoading(web, request()))
        assertEquals(1, launches.size)
        assertEquals(clicked, launches.single().data)
        assertTrue(launches.single().hasExtra(BrowserLinkLauncher.SESSION))
        assertEquals(TextSearchPolicy.googleUrl("selected text"), session.target)
        session.close()
    }

    private fun request(gesture: Boolean = true, redirect: Boolean = false, mainFrame: Boolean = true, method: String = "GET", uri: Uri? = null) = object : WebResourceRequest {
        override fun getUrl() = uri ?: if (!gesture || redirect || method == "POST") android.net.Uri.parse("https://www.google.com/search?q=next") else clicked
        override fun isForMainFrame() = mainFrame
        override fun isRedirect() = redirect
        override fun hasGesture() = gesture
        override fun getMethod() = method
        override fun getRequestHeaders() = emptyMap<String, String>()
    }
}
