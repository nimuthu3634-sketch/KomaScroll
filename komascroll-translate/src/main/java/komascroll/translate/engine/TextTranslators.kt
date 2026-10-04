package komascroll.translate.engine

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Translates a page's bubble texts in one go. Languages are BCP-47 tags such as "ja" or "en". */
interface TextTranslator {
    val id: String

    suspend fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String): List<String>
}

class TranslationException(val reason: Reason, cause: Throwable? = null) : Exception(reason.name, cause) {
    enum class Reason { UNSUPPORTED_LANGUAGE, MODEL_DOWNLOAD_FAILED, INVALID_API_KEY, QUOTA_EXCEEDED, NETWORK }
}

/**
 * On-device translation with ML Kit. The language models (~30 MB each) are downloaded once;
 * [onModelDownload] is invoked if a download is needed.
 */
class MlKitTextTranslator(
    private val requireWifiForDownload: () -> Boolean,
    private val onModelDownload: () -> Unit = {},
) : TextTranslator {

    override val id = ID

    override suspend fun translate(texts: List<String>, sourceLanguage: String, targetLanguage: String): List<String> {
        val source = TranslateLanguage.fromLanguageTag(sourceLanguage)
            ?: throw TranslationException(TranslationException.Reason.UNSUPPORTED_LANGUAGE)
        val target = TranslateLanguage.fromLanguageTag(targetLanguage)
            ?: throw TranslationException(TranslationException.Reason.UNSUPPORTED_LANGUAGE)
        if (source == target) return texts

        val modelManager = RemoteModelManager.getInstance()
        val needsDownload = listOf(source, target).any { language ->
            !modelManager.isModelDownloaded(TranslateRemoteModel.Builder(language).build()).await()
        }
        val client = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build(),
        )
        try {
            if (needsDownload) {
                onModelDownload()
                val conditions = DownloadConditions.Builder()
                    .apply { if (requireWifiForDownload()) requireWifi() }
                    .build()
                try {
                    client.downloadModelIfNeeded(conditions).await()
                } catch (e: Exception) {
                    throw TranslationException(TranslationException.Reason.MODEL_DOWNLOAD_FAILED, e)
                }
            }
            return texts.map { client.translate(it).await() }
        } finally {
            client.close()
        }
    }

    companion object {
        const val ID = "mlkit"
    }
}

/**
 * Online translation with the DeepL API using a user-supplied key (free ":fx" keys use the free
 * endpoint). The key is passed in by the caller from encrypted storage and never persisted here.
 */
class DeepLTextTranslator(
    private val client: OkHttpClient,
    private val apiKey: String,
) : TextTranslator {

    override val id = ID

    @Serializable
    private data class Response(val translations: List<Item>) {
        @Serializable
        data class Item(val text: String)
    }

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun translate(
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String,
    ): List<String> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyList()
        val host = if (apiKey.endsWith(":fx")) "https://api-free.deepl.com" else "https://api.deepl.com"
        val body = FormBody.Builder().apply {
            texts.forEach { add("text", it) }
            add("source_lang", sourceLanguage.substringBefore('-').uppercase())
            add("target_lang", deepLTarget(targetLanguage))
        }.build()
        val request = Request.Builder()
            .url("$host/v2/translate")
            .header("Authorization", "DeepL-Auth-Key $apiKey")
            .post(body)
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw TranslationException(TranslationException.Reason.NETWORK, e)
        }
        response.use {
            when (it.code) {
                200 -> Unit
                401, 403 -> throw TranslationException(TranslationException.Reason.INVALID_API_KEY)
                456 -> throw TranslationException(TranslationException.Reason.QUOTA_EXCEEDED)
                400 -> throw TranslationException(TranslationException.Reason.UNSUPPORTED_LANGUAGE)
                else -> throw TranslationException(TranslationException.Reason.NETWORK, IOException("HTTP ${it.code}"))
            }
            val parsed = json.decodeFromString<Response>(it.body.string())
            parsed.translations.map(Response.Item::text)
        }
    }

    private fun deepLTarget(language: String): String = when (val base = language.substringBefore('-').lowercase()) {
        "en" -> "EN-US"
        "pt" -> "PT-BR"
        "zh" -> "ZH-HANS"
        else -> base.uppercase()
    }

    companion object {
        const val ID = "deepl"
    }
}
