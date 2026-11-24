package com.snipsearch.extraction.isolated

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.Color
import android.os.DeadObjectException
import android.os.SharedMemory
import com.snipsearch.extraction.PixelRegion
import com.snipsearch.ipc.IOcrIsolatedService
import com.snipsearch.ipc.OcrTextBlockParcelable
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode
import java.nio.ByteOrder
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class OcrClientCoordinatorTest {
    private val application = RuntimeEnvironment.getApplication()

    @Test
    fun serviceIsPrivateAndRunsWithAnIsolatedUid() {
        val info = application.packageManager.getServiceInfo(
            ComponentName(application, OcrIsolatedService::class.java),
            PackageManager.ComponentInfoFlags.of(0),
        )
        assertFalse(info.exported)
        assertTrue(info.flags and ServiceInfo.FLAG_ISOLATED_PROCESS != 0)
    }

    @Test
    fun binderDeathReleasesTheBindingAndNextRequestUsesANewServiceWithoutAppFiles() {
        val before = files()
        val context = RecoveringContext(application)
        val coordinator = OcrClientCoordinator(context)
        val crop = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        try {
            assertThrows(DeadObjectException::class.java) { runBlocking { coordinator.recognize(crop) } }
            val line = runBlocking { coordinator.recognize(crop) }.single()
            assertEquals("sample", line.text)
            assertEquals(PixelRegion(0, 1, 8, 6), line.words.single().bounds)
            assertEquals(2, context.bindings)
            assertEquals(2, context.unbindings)
            assertEquals(before, files())
        } finally {
            crop.recycle()
        }
    }

    private fun files() = application.dataDir.walkTopDown()
        .filter { it.isFile }.map { it.relativeTo(application.dataDir).path to it.length() }.toSet()

    private class RecoveringContext(base: Context) : ContextWrapper(base) {
        var bindings = 0
        var unbindings = 0

        override fun bindService(intent: Intent, flags: Int, executor: Executor, conn: ServiceConnection): Boolean {
            val shouldDie = (++bindings == 1)
            val service = object : IOcrIsolatedService.Stub() {
                override fun initializeModels(
                    detector: AssetFileDescriptor, recognizer: AssetFileDescriptor, dictionary: AssetFileDescriptor,
                ) {
                    assertTrue(detector.length > 1_000_000)
                    assertTrue(recognizer.length > 1_000_000)
                    assertTrue(dictionary.length > 20_000)
                }

                override fun recognizeCrop(
                    argbPixels: SharedMemory, width: Int, height: Int, stride: Int,
                ): List<OcrTextBlockParcelable> {
                    val mapping = argbPixels.mapReadOnly()
                    try {
                        assertEquals(Color.MAGENTA, mapping.order(ByteOrder.nativeOrder()).getInt(0))
                    } finally {
                        SharedMemory.unmap(mapping)
                    }
                    if (shouldDie) throw DeadObjectException()
                    return listOf(OcrTextBlockParcelable().apply {
                        text = "sample"
                        wordStarts = intArrayOf(0)
                        wordEnds = intArrayOf(6)
                        wordBounds = intArrayOf(0, 1, 8, 6)
                    })
                }
            }
            executor.execute { conn.onServiceConnected(checkNotNull(intent.component), service) }
            return true
        }

        override fun unbindService(conn: ServiceConnection) {
            unbindings++
        }
    }
}
