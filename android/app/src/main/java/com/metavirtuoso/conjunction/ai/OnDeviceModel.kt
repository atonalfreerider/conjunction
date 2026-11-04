package com.metavirtuoso.conjunction.ai

import android.graphics.Bitmap
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where the phone's on-device model stands, for the UI. */
sealed interface ModelAvailability {
    data object Available : ModelAvailability
    data object Downloadable : ModelAvailability
    data object Downloading : ModelAvailability
    data class Unavailable(val reason: String) : ModelAvailability
}

/** Raised when the on-device model cannot produce text; callers fall back to bundled readings. */
class ModelUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Seam for tests (and for a fake model in screenshots). */
interface TextModel {
    suspend fun availability(): ModelAvailability
    suspend fun generate(prompt: String, image: Bitmap? = null, onStatus: (String) -> Unit = {}): GeneratedText
    fun close()
}

/** Model output plus whether the image (if any) was actually given to the model. */
data class GeneratedText(val text: String, val usedImage: Boolean)

/**
 * Private, foreground-only text generation with Gemini Nano through Android AICore (ML Kit GenAI
 * Prompt API, `com.google.mlkit:genai-prompt`). Same flow as Sundial's HoroscopeGenerator:
 * check status, download if needed with progress messages, generate, clean up. Prompts and
 * answers never leave the phone.
 *
 * Images: genai-prompt 1.0.0-beta5 has no "supports image input" feature flag (Feature lists only
 * SYSTEM_PROMPT, THINKING_MODE, PREFIX_CACHING, STRUCTURED_OUTPUT, FUNCTION_CALLING), and image
 * input depends on the Nano version on the device. So an image request is attempted once; if
 * AICore rejects it with a GenAiException the same prompt is retried as text only, and the
 * result remembers that this device does not take images.
 */
class GeminiNanoModel : TextModel {
    private val model: GenerativeModel by lazy { Generation.getClient() }
    private val lock = Mutex()
    @Volatile private var imagesRejected = false

    override suspend fun availability(): ModelAvailability = try {
        when (model.checkStatus()) {
            FeatureStatus.AVAILABLE -> ModelAvailability.Available
            FeatureStatus.DOWNLOADABLE -> ModelAvailability.Downloadable
            FeatureStatus.DOWNLOADING -> ModelAvailability.Downloading
            else -> ModelAvailability.Unavailable("Gemini Nano is not available on this device configuration.")
        }
    } catch (e: GenAiException) {
        ModelAvailability.Unavailable(e.message ?: "AICore is not available on this device.")
    } catch (e: RuntimeException) {
        // Devices without AICore / Play services report through runtime exceptions.
        ModelAvailability.Unavailable(e.message ?: "On-device AI is not available on this device.")
    }

    override suspend fun generate(prompt: String, image: Bitmap?, onStatus: (String) -> Unit): GeneratedText = lock.withLock {
        ensureReady(onStatus)
        onStatus("Writing your reading on this phone…")
        try {
            if (image != null && !imagesRejected) {
                try {
                    val response = model.generateContent(generateContentRequest(ImagePart(image), TextPart(prompt)) {
                        temperature = TEMPERATURE
                        topK = TOP_K
                    })
                    return@withLock GeneratedText(firstText(response.candidates.map { it.text }), usedImage = true)
                } catch (e: GenAiException) {
                    imagesRejected = true
                    onStatus("Reading the palm measurements…")
                }
            }
            val response = model.generateContent(generateContentRequest(TextPart(prompt)) {
                temperature = TEMPERATURE
                topK = TOP_K
            })
            GeneratedText(firstText(response.candidates.map { it.text }), usedImage = false)
        } catch (e: GenAiException) {
            throw ModelUnavailableException(e.message ?: "Gemini Nano could not write a reading.", e)
        }
    }

    private suspend fun ensureReady(onStatus: (String) -> Unit) {
        when (availability()) {
            ModelAvailability.Available -> Unit
            ModelAvailability.Downloadable, ModelAvailability.Downloading -> {
                onStatus("Preparing Gemini Nano on this device…")
                var failed: Throwable? = null
                model.download().collect { status ->
                    when (status) {
                        is DownloadStatus.DownloadStarted -> onStatus("Downloading the private on-device model…")
                        is DownloadStatus.DownloadProgress ->
                            onStatus("Downloading Gemini Nano… ${status.totalBytesDownloaded / 1_000_000} MB")
                        DownloadStatus.DownloadCompleted -> onStatus("Reading the celestial pattern…")
                        is DownloadStatus.DownloadFailed -> failed = status.e
                    }
                }
                failed?.let { throw ModelUnavailableException("Gemini Nano download failed.", it) }
                if (availability() != ModelAvailability.Available) {
                    throw ModelUnavailableException("Gemini Nano is still preparing. Try again in a few minutes.")
                }
            }
            is ModelAvailability.Unavailable -> throw ModelUnavailableException("Gemini Nano is not available on this device.")
        }
    }

    private fun firstText(candidates: List<String?>): String =
        candidates.firstOrNull { !it.isNullOrBlank() }?.trim()
            ?: throw ModelUnavailableException("Gemini Nano returned no text. Please try again.")

    override fun close() {
        runCatching { model.close() }
    }

    private companion object {
        const val TEMPERATURE = 0.8f
        const val TOP_K = 40
    }
}
