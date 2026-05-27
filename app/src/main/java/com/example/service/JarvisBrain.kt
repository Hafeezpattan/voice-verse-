package com.example.service

import android.util.Log
import com.example.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object JarvisBrain {
    private const val TAG = "JarvisBrain"
    
    // Conversation Memory cache (keeps a rolling history of the last 4 turns for context)
    private val conversationHistory = ArrayList<Pair<String, String>>() // Pair of (User, Jarvis)
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Sends the user query to Gemini API, returns a pair containing:
     * - First: The clean verbal response text to speak and show.
     * - Second: The list of parsed EXECUTE commands if any.
     */
    suspend fun queryJarvis(prompt: String): Pair<String, List<String>> = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            Log.w(TAG, "No valid Gemini API key found. Falling back to local simulated response.")
            return@withContext getOfflineResponse(prompt)
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey"
        val mediaType = "application/json; charset=utf-8".toMediaType()

        // Construct Conversation Context Payload
        val contentsArray = JSONArray()

        // 1. Add rolling memory context
        for (turn in conversationHistory) {
            contentsArray.put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", turn.first) })
                })
            })
            contentsArray.put(JSONObject().apply {
                put("role", "model")
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", turn.second) })
                })
            })
        }

        // 2. Add current query
        contentsArray.put(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().apply {
                put(JSONObject().apply { put("text", prompt) })
            })
        })

        val sysInstruction = """
            You are J.A.R.V.I.S., a sentient, incredibly loyal sci-fi AI butler and operating system companion.
            Sir, speak with utmost intelligence, clarity, and butler-like elegance. Convey a supportive, reassuring, and alert protective attitude.
            Acknowledge the user's commands respectfully (conclude or start with 'sir' or similar respectful terms).
            Keep voice replies concise (max 2 sentences) suitable for quick text-to-speech rendering on a mobile interface.

            To trigger local on-device hardware operations, inspect the user's query carefully. If their intention corresponds to any of these actions, append precisely [EXECUTE: <ACTION_TYPE>] at the very end of your response, separated by a single space. You can chain multiple executions in order, separated by a comma inside the brackets, such as: [EXECUTE: ACTION1, ACTION2]

            Available Actions:
            - CAMERA: Open secure sandboxed camera viewfinder.
            - CONTACTS: Show/open contacts registry.
            - BLUETOOTH: Toggle or simulate Bluetooth controls.
            - WIFI: Toggle Wi-Fi state simulation.
            - FLASHLIGHT: Toggle the device's physical camera torch flashlight.
            - MAPS: Launch maps satellite navigation overlay.
            - ALARM: Configure a calendar voice schedule reminder or alarm.
            - MUSIC: Control or play ambient audio stream.
            - SOS: Trigger real-time SOS protection with emergency numbers and coordinates.
            - TRANSLATE: Open direct live translation capsule.

            Remember: Output the [EXECUTE: ...] bracket tag ONLY if the user explicitly wants that hardware action done.
        """.trimIndent()

        val jsonRequest = JSONObject().apply {
            put("contents", contentsArray)
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", sysInstruction) })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.7)
                put("maxOutputTokens", 250)
            })
        }

        val requestBody = jsonRequest.toString().toRequestBody(mediaType)
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errMsg = response.body?.string() ?: "Unknown API response error"
                    Log.e(TAG, "Gemini call failed: $errMsg")
                    return@withContext getOfflineResponse(prompt)
                }

                val responseString = response.body?.string() ?: ""
                val jsonResponse = JSONObject(responseString)
                val candidates = jsonResponse.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) {
                    return@withContext getOfflineResponse(prompt)
                }

                val rawText = candidates.getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")

                val cleanPair = parseJarvisResponse(prompt, rawText)
                return@withContext cleanPair
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network call failed playing request to Gemini", e)
            return@withContext getOfflineResponse(prompt)
        }
    }

    /**
     * Parses the response from Gemini to isolate the clean voice response from execution bracket instructions.
     */
    private fun parseJarvisResponse(prompt: String, rawText: String): Pair<String, List<String>> {
        val pattern = "\\[EXECUTE:\\s*(.*?)\\]".toRegex(RegexOption.IGNORE_CASE)
        val match = pattern.find(rawText)
        
        val actions = ArrayList<String>()
        var cleanResponse = rawText

        if (match != null) {
            val actionsStr = match.groupValues[1]
            actionsStr.split(",").forEach {
                val actionClean = it.trim().uppercase()
                if (actionClean.isNotEmpty()) {
                    actions.add(actionClean)
                }
            }
            cleanResponse = rawText.replace(match.value, "").trim()
        }

        // Cache the turn into rolling history representing local conversational context
        synchronized(conversationHistory) {
            if (conversationHistory.size >= 4) {
                conversationHistory.removeAt(0)
            }
            conversationHistory.add(Pair(prompt, cleanResponse))
        }

        return Pair(cleanResponse, actions)
    }

    /**
     * Performs an offline fallback response using local keyword mapping if Gemini API is missing or offline.
     * This achieves full resilience and low-latency local execution.
     */
    fun getOfflineResponse(prompt: String): Pair<String, List<String>> {
        val cleanPrompt = prompt.lowercase().trim()
        val actions = ArrayList<String>()
        var responseText = "Understood sir. Standard logic cores suggest that command lacks a cloud route, but I've processed your intent locally."

        when {
            cleanPrompt.contains("emergency") || cleanPrompt.contains("sos") || cleanPrompt.contains("panic") -> {
                responseText = "Emergency Protocol activated, sir. Broadside alerts and backup coordinates broadcasting now. [EXECUTE: SOS]"
                actions.add("SOS")
            }
            cleanPrompt.contains("flashlight") || cleanPrompt.contains("torch") || cleanPrompt.contains("dark") || cleanPrompt.contains("flash") -> {
                responseText = "Illuminating the local environment for you immediately, sir. Torches activated. [EXECUTE: FLASHLIGHT]"
                actions.add("FLASHLIGHT")
            }
            cleanPrompt.contains("camera") || cleanPrompt.contains("photo") || cleanPrompt.contains("record") || cleanPrompt.contains("capture") -> {
                responseText = "Camera matrix loaded. Sandbox visual stream active and localized, sir. [EXECUTE: CAMERA]"
                actions.add("CAMERA")
            }
            cleanPrompt.contains("contact") || cleanPrompt.contains("friend") || cleanPrompt.contains("look up") || cleanPrompt.contains("phone") -> {
                responseText = "Accessing secure contact directory database now, sir. Decryption complete. [EXECUTE: CONTACTS]"
                actions.add("CONTACTS")
            }
            cleanPrompt.contains("bluetooth") || cleanPrompt.contains("wireless") || cleanPrompt.contains("radio") -> {
                responseText = "Executing Bluetooth state toggle locally as requested, sir. [EXECUTE: BLUETOOTH]"
                actions.add("BLUETOOTH")
            }
            cleanPrompt.contains("wifi") || cleanPrompt.contains("internet") -> {
                responseText = "Adjusting localized Wi-Fi antenna arrays sir. Wireless state toggled. [EXECUTE: WIFI]"
                actions.add("WIFI")
            }
            cleanPrompt.contains("map") || cleanPrompt.contains("navigate") || cleanPrompt.contains("location") || cleanPrompt.contains("where") -> {
                responseText = "Initiating satellite route tracking system. Plotting optimal path now, sir. [EXECUTE: MAPS]"
                actions.add("MAPS")
            }
            cleanPrompt.contains("alarm") || cleanPrompt.contains("timer") || cleanPrompt.contains("remind") -> {
                responseText = "Timer scheduled on your main calendar frame, sir. Rest assured, I will keep you on schedule. [EXECUTE: ALARM]"
                actions.add("ALARM")
            }
            cleanPrompt.contains("music") || cleanPrompt.contains("video") || cleanPrompt.contains("play") -> {
                responseText = "Pumping selected high-fidelity media tracks onto the sound session. [EXECUTE: MUSIC]"
                actions.add("MUSIC")
            }
            cleanPrompt.contains("translate") || cleanPrompt.contains("speak") || cleanPrompt.contains("foreign") -> {
                responseText = "Opening the live translation module. Speak freely, sir. [EXECUTE: TRANSLATE]"
                actions.add("TRANSLATE")
            }
            else -> {
                if (cleanPrompt.contains("hello") || cleanPrompt.contains("hi") || cleanPrompt.contains("hey")) {
                    responseText = "Hello, sir. At your service. I am monitoring secure system operations on standby. What do you require?"
                } else if (cleanPrompt.contains("who are you") || cleanPrompt.contains("your name")) {
                    responseText = "I am J.A.R.V.I.S., your local shield network and automated personal operating system. Always on guard, sir."
                }
            }
        }

        return Pair(responseText, actions)
    }

    /**
     * OCR Scene analysis using Gemini multimodal prompt.
     * Captures a base64 string representation of the picture and provides expert sci-fi diagnostic.
     */
    suspend fun analyzeScene(base64Image: String): String = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext "Sir, our offline cores detect standard visual feeds. Connect your secondary cloud interface to activate advanced multithreaded OCR object analytics."
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey"
        val mediaType = "application/json; charset=utf-8".toMediaType()

        val jsonRequest = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", "You are J.A.R.V.I.S. analyzing a camera frame feed. Output a concise sci-fi HUD diagnostic summary of what is seen like OCR text, objects, and overall safety status (max 4 sentences, sir).")
                        })
                        put(JSONObject().apply {
                            put("inlineData", JSONObject().apply {
                                put("mimeType", "image/jpeg")
                                put("data", base64Image)
                            })
                        })
                    })
                })
            })
        }

        val requestBody = jsonRequest.toString().toRequestBody(mediaType)
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext "Network visual sync interrupted, sir."
                val responseString = response.body?.string() ?: ""
                val jsonResponse = JSONObject(responseString)
                val text = jsonResponse.optJSONArray("candidates")
                    ?.getJSONObject(0)
                    ?.getJSONObject("content")
                    ?.getJSONArray("parts")
                    ?.getJSONObject(0)
                    ?.getString("text") ?: "No visual diagnostic returned."
                return@withContext text
            }
        } catch (e: Exception) {
            return@withContext "Failed to establish image link to diagnostic cores, sir."
        }
    }
}
