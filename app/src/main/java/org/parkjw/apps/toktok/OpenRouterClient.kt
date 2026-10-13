package org.parkjw.apps.toktok

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** Minimal OpenRouter client: STT transcription, correction chat, model list. */
object OpenRouterClient {

    private const val STT_URL = "https://openrouter.ai/api/v1/audio/transcriptions"
    private const val CHAT_URL = "https://openrouter.ai/api/v1/chat/completions"
    private const val MODELS_URL = "https://openrouter.ai/api/v1/models"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    const val SYSTEM_PROMPT =
        "너는 한국어 음성 받아쓰기 윤문 도우미다. 규칙: 맞춤법과 문법을 고치고, " +
            "말버릇(음, 어, 그니까 등)을 제거하고, 문장부호를 넣고, 말하다가 스스로 정정한 부분" +
            "(예: \"아니 방금 건 빼고\")을 뜻에 맞게 반영하라. 말하지 않은 내용을 절대 지어내지 마라. " +
            "정정된 텍스트만 출력하고 설명은 덧붙이지 마라. 한국어와 영어를 모두 처리한다."

    fun isTranscriptionModel(id: String): Boolean {
        val l = id.lowercase()
        return "whisper" in l || "transcri" in l || "asr" in l ||
            ("audio" in l && ("speech" in l || "trans" in l))
    }

    suspend fun transcribe(
        apiKey: String, model: String, wav: ByteArray, language: String? = null,
        provider: String? = null
    ): String {
        val audio = JSONObject()
            .put("data", Base64.encodeToString(wav, Base64.NO_WRAP))
            .put("format", "wav")
        val body = JSONObject()
            .put("model", model)
            .put("input_audio", audio)
        if (!language.isNullOrBlank()) body.put("language", language)
        applyProvider(body, provider)
        return postJson(STT_URL, apiKey, body).optString("text", "").trim()
    }

    suspend fun correct(
        apiKey: String, model: String, raw: String, provider: String? = null
    ): String {
        val body = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                    .put(JSONObject().put("role", "user").put("content", raw))
            )
        applyProvider(body, provider)
        return postJson(CHAT_URL, apiKey, body)
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content", "")
            ?.trim()
            .orEmpty()
    }

    suspend fun listModels(apiKey: String): List<String> {
        val req = Request.Builder()
            .url(MODELS_URL)
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        val text = execute(req)
        val data = JSONObject(text).optJSONArray("data") ?: JSONArray()
        return List(data.length()) { i -> data.optJSONObject(i)?.optString("id", "") ?: "" }
            .filter { it.isNotBlank() }
            .sorted()
    }

    private fun applyProvider(body: JSONObject, provider: String?) {
        provider?.takeIf { it.isNotBlank() }?.let {
            body.put(
                "provider", JSONObject()
                    .put("order", JSONArray().put(it))
                    .put("allow_fallbacks", true)
            )
        }
    }

    /** POST JSON with true coroutine cancellation (Call.cancel interrupts the socket). */
    private suspend fun postJson(url: String, apiKey: String, body: JSONObject): JSONObject {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody(JSON))
            .build()
        val text = execute(req)
        return JSONObject(text)
    }

    private suspend fun execute(req: Request): String =
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { cont ->
                val call = client.newCall(req)
                cont.invokeOnCancellation { call.cancel() }
                try {
                    call.execute().use { resp ->
                        val text = resp.body?.string() ?: ""
                        if (!resp.isSuccessful) {
                            cont.resumeWithException(
                                IllegalStateException("요청 실패(HTTP ${resp.code}): ${text.take(200)}")
                            )
                        } else {
                            cont.resume(text, null)
                        }
                    }
                } catch (e: Exception) {
                    if (!cont.isCompleted) cont.resumeWithException(e)
                }
            }
        }
}
