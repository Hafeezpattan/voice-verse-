package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.security.SecureStorageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context matches updated app name`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Voice Guard", appName)
    }

    @Test
    fun `secure SHA-256 hashing verifies consistently`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val secureStorage = SecureStorageManager(context)

        val input1 = "Open Camera Now"
        val input2 = "Open Camera Now"
        val input3 = "Different command trigger"

        val hash1 = secureStorage.sha256(input1)
        val hash2 = secureStorage.sha256(input2)
        val hash3 = secureStorage.sha256(input3)

        // Hashing are deterministic
        assertEquals(hash1, hash2)
        // Hashing outputs are different for different strings
        assertTrue(hash1 != hash3)
        // Correct length of MD SHA-256 in hex formatting is 64 characters
        assertEquals(64, hash1.length)
    }

    @Test
    fun `default voiceprint matches matching default speech sequences`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val secureStorage = SecureStorageManager(context)

        // Before any master is set, "activate security" simulates successfully as fallback default
        val resultSuccess = secureStorage.verifyVoiceprint("activate security")
        assertTrue(resultSuccess.isMatched)

        val resultSuccessCamera = secureStorage.verifyVoiceprint("please open camera")
        assertTrue(resultSuccessCamera.isMatched)

        // Random talking should be blocked
        val resultBlocked = secureStorage.verifyVoiceprint("hello from the emulator")
        assertFalse(resultBlocked.isMatched)
    }
}
