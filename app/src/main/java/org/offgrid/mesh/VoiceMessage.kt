package org.offgrid.mesh

import android.content.Context
import android.media.MediaPlayer
import android.util.Base64
import java.io.File
import java.util.Locale

/** Compact, offline voice payload carried through the same encrypted mesh packet as text. */
object VoiceMessage {
    private const val PREFIX = "OFFGRID_VOICE_V1"
    private const val MAX_BYTES = 1_000_000
    data class Clip(val language: String, val audio: ByteArray)

    fun encode(file: File, language: String): String {
        val bytes = file.readBytes()
        require(bytes.size <= MAX_BYTES) { "Voice clip is too large. Keep it under 30 seconds." }
        return "$PREFIX|$language|${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
    }

    fun decode(payload: String): Clip? = try {
        val pieces = payload.split("|", limit = 3)
        if (pieces.size != 3 || pieces[0] != PREFIX) null
        else Clip(pieces[1], Base64.decode(pieces[2], Base64.NO_WRAP))
    } catch (_: Exception) { null }

    fun play(context: Context, packetId: String, payload: String) {
        val clip = decode(payload) ?: return
        val file = File(context.cacheDir, "voice-$packetId.m4a")
        file.writeBytes(clip.audio)
        MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnPreparedListener { it.start() }
            setOnCompletionListener { it.release() }
            prepareAsync()
        }
    }

    val languages = arrayOf(
        "Assamese",
        "Bengali",
        "Bodo",
        "Dogri",
        "English",
        "Gujarati",
        "Hindi",
        "Kannada",
        "Kashmiri",
        "Konkani",
        "Maithili",
        "Malayalam",
        "Manipuri",
        "Marathi",
        "Nepali",
        "Odia",
        "Punjabi",
        "Sanskrit",
        "Santali",
        "Sindhi",
        "Tamil",
        "Telugu",
        "Urdu"
    )

    val whisperLanguageCodes = mapOf(
        "Assamese" to "as",
        "Bengali" to "bn",
        "Bodo" to "auto",
        "Dogri" to "auto",
        "English" to "en",
        "Gujarati" to "gu",
        "Hindi" to "hi",
        "Kannada" to "kn",
        "Kashmiri" to "auto",
        "Konkani" to "auto",
        "Maithili" to "auto",
        "Malayalam" to "ml",
        "Manipuri" to "auto",
        "Marathi" to "mr",
        "Nepali" to "ne",
        "Odia" to "auto",
        "Punjabi" to "pa",
        "Sanskrit" to "sa",
        "Santali" to "auto",
        "Sindhi" to "sd",
        "Tamil" to "ta",
        "Telugu" to "te",
        "Urdu" to "ur"
    )


    /** Whisper language code for the selected language, or "auto" when it is not directly supported. */
    fun whisperLanguageCode(language: String): String =
        whisperLanguageCodes[language] ?: "auto"

    val languageTags = mapOf(
        "Assamese" to "as-IN",
        "Bengali" to "bn-IN",
        "Bodo" to "brx-IN",
        "Dogri" to "doi-IN",
        "English" to "en-IN",
        "Gujarati" to "gu-IN",
        "Hindi" to "hi-IN",
        "Kannada" to "kn-IN",
        "Kashmiri" to "ks-IN",
        "Konkani" to "kok-IN",
        "Maithili" to "mai-IN",
        "Malayalam" to "ml-IN",
        "Manipuri" to "mni-IN",
        "Marathi" to "mr-IN",
        "Nepali" to "ne-NP",
        "Odia" to "or-IN",
        "Punjabi" to "pa-IN",
        "Sanskrit" to "sa-IN",
        "Santali" to "sat-IN",
        "Sindhi" to "sd-IN",
        "Tamil" to "ta-IN",
        "Telugu" to "te-IN",
        "Urdu" to "ur-IN"
    )


    /** The system locale is the safest tag for the default English recognizer on OEM devices. */
    fun recognizerLanguageTag(language: String): String =
        if (language == "English") Locale.getDefault().toLanguageTag()
        else languageTags[language] ?: Locale.getDefault().toLanguageTag()
}
