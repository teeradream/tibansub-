package com.metallic.chiaki.tibansub.ui.presettings

import android.content.Context
import com.metallic.chiaki.common.AppStateBackupManager
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.stream.StreamActivity

/**
 * Two-way synchronized state holder reading & writing the exact same SharedPreferences keys
 * ("ps_translator_crop" + default Chiaki Preferences) used by StreamActivity's 3-line (☰) menu.
 */
data class PreSettingsSnapshot(
    val subOverlayEnabled: Boolean = true,
    val subTextColorHex: String = "#00FF88",
    val subFontSizeSp: Int = 26,
    val subPosition: String = "BOTTOM",
    val subBgEnabled: Boolean = true,
    val subBgOpacity: Int = 80,
    val subHistoryLimit: Int = 3,
    val popupZonesMode: Boolean = false,
    val filterShortUi: Boolean = true,
    val ttsVoiceEnabled: Boolean = false,
    val geminiTtsEnabled: Boolean = true,
    val voiceGenderMode: String = "AUTO_AI",
    val speechSpeedRate: Float = 1.25f,
    val ttsQueueMode: String = "SMART_QUEUE",
    val gameAudioMuted: Boolean = false,
    val tvCastOverlayEnabled: Boolean = true,
    val localCacheEnabled: Boolean = true,
    val activeProvider: StreamActivity.TranslationProvider = StreamActivity.TranslationProvider.GOOGLE_FREE,
    val customApiUrl: String = "",
    val providerKeys: Map<StreamActivity.TranslationProvider, String> = emptyMap(),
    val providerModels: Map<StreamActivity.TranslationProvider, String> = emptyMap(),
    val resolution: Preferences.Resolution = Preferences.Resolution.RES_720P,
    val fps: Preferences.FPS = Preferences.FPS.FPS_60,
    val codec: Preferences.Codec = Preferences.Codec.CODEC_H265,
    val onScreenControls: Boolean = false
)

class PreSettingsRepository(private val context: Context) {
    private val cropPrefs = context.getSharedPreferences("ps_translator_crop", Context.MODE_PRIVATE)
    private val streamPrefs = Preferences(context)

    private fun keyPrefName(provider: StreamActivity.TranslationProvider): String = when (provider) {
        StreamActivity.TranslationProvider.GEMINI -> "gemini_api_key"
        StreamActivity.TranslationProvider.GROQ -> "api_key_groq"
        StreamActivity.TranslationProvider.CEREBRAS -> "api_key_cerebras"
        StreamActivity.TranslationProvider.OPENAI -> "api_key_openai"
        StreamActivity.TranslationProvider.OPENROUTER -> "api_key_openrouter"
        StreamActivity.TranslationProvider.CUSTOM -> "api_key_custom"
        StreamActivity.TranslationProvider.GOOGLE_FREE -> ""
    }

    private fun modelPrefName(provider: StreamActivity.TranslationProvider): String =
        "api_model_${provider.name.lowercase()}"

    fun load(): PreSettingsSnapshot {
        val savedProv = cropPrefs.getString("translation_provider", null)
        val provider = try {
            if (savedProv != null) StreamActivity.TranslationProvider.valueOf(savedProv)
            else StreamActivity.TranslationProvider.GOOGLE_FREE
        } catch (_: Exception) {
            StreamActivity.TranslationProvider.GOOGLE_FREE
        }

        val keys = mutableMapOf<StreamActivity.TranslationProvider, String>()
        val models = mutableMapOf<StreamActivity.TranslationProvider, String>()
        for (p in StreamActivity.TranslationProvider.values()) {
            val kName = keyPrefName(p)
            keys[p] = if (kName.isEmpty()) "" else (cropPrefs.getString(kName, "") ?: "")
            models[p] = if (p == StreamActivity.TranslationProvider.GOOGLE_FREE) ""
            else (cropPrefs.getString(modelPrefName(p), "")?.ifBlank { p.defaultModel } ?: p.defaultModel)
        }

        return PreSettingsSnapshot(
            subOverlayEnabled = cropPrefs.getBoolean("sub_overlay_enabled", true),
            subTextColorHex = cropPrefs.getString("sub_text_color", "#00FF88") ?: "#00FF88",
            subFontSizeSp = cropPrefs.getInt("sub_font_size_sp", 26).coerceIn(10, 80),
            subPosition = cropPrefs.getString("sub_position", "BOTTOM") ?: "BOTTOM",
            subBgEnabled = cropPrefs.getBoolean("sub_bg_enabled", true),
            subBgOpacity = cropPrefs.getInt("sub_bg_opacity", 80).coerceIn(0, 100),
            subHistoryLimit = cropPrefs.getInt("sub_history_limit", 3).coerceIn(1, 3),
            popupZonesMode = cropPrefs.getBoolean("popup_zones_mode", false),
            filterShortUi = cropPrefs.getBoolean("filter_short_ui", true),
            ttsVoiceEnabled = cropPrefs.getBoolean("tts_voice_enabled", false),
            geminiTtsEnabled = cropPrefs.getBoolean("gemini_tts_enabled", true),
            voiceGenderMode = cropPrefs.getString("voice_gender_mode", "AUTO_AI") ?: "AUTO_AI",
            speechSpeedRate = cropPrefs.getFloat("speech_speed_rate", 1.25f),
            ttsQueueMode = cropPrefs.getString("tts_queue_mode", "SMART_QUEUE") ?: "SMART_QUEUE",
            gameAudioMuted = cropPrefs.getBoolean("game_audio_muted", false),
            tvCastOverlayEnabled = cropPrefs.getBoolean("tv_cast_overlay_enabled", true),
            localCacheEnabled = cropPrefs.getBoolean("local_cache_enabled", true),
            activeProvider = provider,
            customApiUrl = cropPrefs.getString("api_custom_url", "")
                ?.ifBlank { StreamActivity.TranslationProvider.CUSTOM.defaultEndpoint }
                ?: StreamActivity.TranslationProvider.CUSTOM.defaultEndpoint,
            providerKeys = keys,
            providerModels = models,
            resolution = streamPrefs.resolution,
            fps = streamPrefs.fps,
            codec = streamPrefs.codec,
            onScreenControls = streamPrefs.onScreenControlsEnabled
        )
    }

    fun save(snapshot: PreSettingsSnapshot) {
        val ed = cropPrefs.edit()
            .putBoolean("sub_overlay_enabled", snapshot.subOverlayEnabled)
            .putString("sub_text_color", snapshot.subTextColorHex)
            .putInt("sub_font_size_sp", snapshot.subFontSizeSp.coerceIn(10, 80))
            .putString("sub_position", snapshot.subPosition)
            .putInt("sub_vertical_percent", if (snapshot.subPosition == "CENTER") 50 else 15)
            .putBoolean("sub_bg_enabled", snapshot.subBgEnabled)
            .putInt("sub_bg_opacity", snapshot.subBgOpacity.coerceIn(0, 100))
            .putInt("sub_history_limit", snapshot.subHistoryLimit.coerceIn(1, 3))
            .putBoolean("popup_zones_mode", snapshot.popupZonesMode)
            .putBoolean("filter_short_ui", snapshot.filterShortUi)
            .putBoolean("tts_voice_enabled", snapshot.ttsVoiceEnabled)
            .putBoolean("gemini_tts_enabled", snapshot.geminiTtsEnabled)
            .putString("voice_gender_mode", snapshot.voiceGenderMode)
            .putFloat("speech_speed_rate", snapshot.speechSpeedRate)
            .putString("tts_queue_mode", snapshot.ttsQueueMode)
            .putBoolean("game_audio_muted", snapshot.gameAudioMuted)
            .putBoolean("tv_cast_overlay_enabled", snapshot.tvCastOverlayEnabled)
            .putBoolean("local_cache_enabled", snapshot.localCacheEnabled)
            .putString("translation_provider", snapshot.activeProvider.name)
            .putString("api_custom_url", snapshot.customApiUrl.trim())

        snapshot.providerKeys.forEach { (prov, key) ->
            val kName = keyPrefName(prov)
            if (kName.isNotEmpty()) ed.putString(kName, key.trim())
        }
        snapshot.providerModels.forEach { (prov, model) ->
            if (prov != StreamActivity.TranslationProvider.GOOGLE_FREE) {
                ed.putString(modelPrefName(prov), model.trim())
            }
        }
        ed.apply()

        streamPrefs.resolution = snapshot.resolution
        streamPrefs.fps = snapshot.fps
        streamPrefs.codec = snapshot.codec
        streamPrefs.onScreenControlsEnabled = snapshot.onScreenControls

        AppStateBackupManager.autoBackupAsync(context)
    }
}
