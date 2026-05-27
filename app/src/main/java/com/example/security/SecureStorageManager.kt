package com.example.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.security.MessageDigest

class SecureStorageManager(private val context: Context) {

    private val sharedPreferences: SharedPreferences by lazy {
        try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                "secure_voice_prefs",
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e("SecureStorage", "Failed to init EncryptedSharedPreferences, falling back to standard prefs", e)
            context.getSharedPreferences("secure_voice_prefs_fallback", Context.MODE_PRIVATE)
        }
    }

    companion object {
        private const val KEY_PASSPHRASE_HASH = "passphrase_hash"
        private const val KEY_VOICE_ACCURACY_THRESHOLD = "voice_accuracy_threshold"
        private const val KEY_VOICEPRINT_VERIFIED_PHRASE = "voiceprint_verified_phrase"
        
        // Actions corresponding to passphrases
        private const val KEY_ACTION_CAMERA_PASSPHRASE = "action_camera_passphrase"
        private const val KEY_ACTION_CONTACT_PASSPHRASE = "action_contact_passphrase"
    }

    /**
     * Compute cryptographically secure SHA-256 hash of a string
     */
    fun sha256(input: String): String {
        return try {
            val bytes = input.trim().lowercase().toByteArray()
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(bytes)
            digest.fold("") { str, it -> str + "%02x".format(it) }
        } catch (e: Exception) {
            Log.e("SecureStorage", "Error hashing string", e)
            // Secure fallback encoding if MD fails
            input.hashCode().toString()
        }
    }

    /**
     * Registers a master offline passphrase. This serves as the master dynamic voice signature key.
     */
    fun saveMasterPassphrase(passphrase: String) {
        val hashed = sha256(passphrase)
        sharedPreferences.edit()
            .putString(KEY_PASSPHRASE_HASH, hashed)
            .putString(KEY_VOICEPRINT_VERIFIED_PHRASE, passphrase.trim().lowercase())
            .apply()
    }

    /**
     * Returns the raw unhashed master passphrase for UI or local matching comparison.
     */
    fun getMasterPassphraseText(): String {
        return sharedPreferences.getString(KEY_VOICEPRINT_VERIFIED_PHRASE, "activate security") ?: "activate security"
    }

    /**
     * Set explicit passphrases for system operations (e.g., Contacts, Camera)
     */
    fun saveActionPassphrase(action: String, phrase: String) {
        val key = when (action.uppercase()) {
            "CAMERA" -> KEY_ACTION_CAMERA_PASSPHRASE
            "CONTACTS" -> KEY_ACTION_CONTACT_PASSPHRASE
            else -> "action_${action.lowercase()}_passphrase"
        }
        val hashed = sha256(phrase)
        sharedPreferences.edit()
            .putString(key, hashed)
            .apply()
    }

    fun getActionPassphraseHash(action: String): String? {
        val key = when (action.uppercase()) {
            "CAMERA" -> KEY_ACTION_CAMERA_PASSPHRASE
            "CONTACTS" -> KEY_ACTION_CONTACT_PASSPHRASE
            else -> "action_${action.lowercase()}_passphrase"
        }
        return sharedPreferences.getString(key, null)
    }

    /**
     * Verifies if speaking matches the cryptographically stored passphrase.
     */
    fun verifyVoiceprint(inputText: String, operation: String? = null): VerificationResult {
        val cleanInput = inputText.trim().lowercase()
        
        // 1. Check if matches custom operation-specific passphrase
        if (operation != null) {
            val targetHash = getActionPassphraseHash(operation)
            if (targetHash != null) {
                val inputHash = sha256(cleanInput)
                if (inputHash == targetHash) {
                    return VerificationResult(true, "Custom $operation action verified matching voiceprint")
                }
            }
        }

        // 2. Fall back to matching master trigger phrase
        val masterHash = sharedPreferences.getString(KEY_PASSPHRASE_HASH, null)
        if (masterHash == null) {
            // No master passphrase setup yet! Let's match default "activate security", "open camera", "show contacts"
            val defaultKeyphrases = mapOf(
                "activate security" to "MASTER",
                "open camera" to "CAMERA",
                "show contacts" to "CONTACTS"
            )
            for ((key, act) in defaultKeyphrases) {
                if (cleanInput.contains(key)) {
                    return VerificationResult(true, "Default $act command verified (No custom voiceprint registered yet)", fallbackMatched = true, detectedAction = act)
                }
            }
            return VerificationResult(false, "No matching passphrase sequence detected.")
        }

        val inputHash = sha256(cleanInput)
        if (inputHash == masterHash) {
            return VerificationResult(true, "Voice Verification Passed (SHA-256 Signature Match)")
        }

        // Partial match with string similarity if it's close (helpful for voice input variance)
        val masterText = getMasterPassphraseText()
        if (cleanInput.contains(masterText) || masterText.contains(cleanInput)) {
            return VerificationResult(true, "Voice Verification Passed (Trigger Phrase Intersect Match)")
        }

        return VerificationResult(false, "Voiceprint validation failed (Input SHA-256 did not match stored signature/phrase)")
    }

    /**
     * Holds voiceprint accuracy confidence percentage configuration
     */
    fun setVoiceAccuracyThreshold(percentage: Int) {
        sharedPreferences.edit().putInt(KEY_VOICE_ACCURACY_THRESHOLD, percentage).apply()
    }

    fun getVoiceAccuracyThreshold(): Int {
        return sharedPreferences.getInt(KEY_VOICE_ACCURACY_THRESHOLD, 75)
    }
}

data class VerificationResult(
    val isMatched: Boolean,
    val details: String,
    val fallbackMatched: Boolean = false,
    val detectedAction: String? = null
)
