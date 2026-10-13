package org.parkjw.apps.toktok

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Plain prefs (button position, toggles) + encrypted prefs (API key, models). */
object Prefs {
    const val KEY_X_RATIO = "btn_x_ratio"
    const val KEY_Y_RATIO = "btn_y_ratio"
    const val KEY_API = "openrouter_api_key"
    const val KEY_STT_MODEL = "stt_model"
    const val KEY_CORR_MODEL = "correction_model"
    const val KEY_BTN_IMAGE = "button_image_path"
    const val KEY_PROVIDER = "preferred_provider"
    const val KEY_SKIP_CORRECTION = "skip_correction"

    const val DEFAULT_STT_MODEL = "openai/whisper-large-v3"
    const val DEFAULT_CORR_MODEL = "deepseek/deepseek-v4.1-flash"

    private fun plain(c: Context) =
        c.getSharedPreferences("toktok_prefs", Context.MODE_PRIVATE)

    private fun secure(c: Context) = EncryptedSharedPreferences.create(
        c,
        "toktok_secure",
        MasterKey.Builder(c).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getApiKey(c: Context): String = try {
        secure(c).getString(KEY_API, "") ?: ""
    } catch (_: Exception) {
        ""
    }

    fun setApiKey(c: Context, v: String) {
        try {
            secure(c).edit().putString(KEY_API, v).apply()
        } catch (_: Exception) {
        }
    }

    fun getSttModel(c: Context): String = try {
        secure(c).getString(KEY_STT_MODEL, DEFAULT_STT_MODEL) ?: DEFAULT_STT_MODEL
    } catch (_: Exception) {
        DEFAULT_STT_MODEL
    }

    fun setSttModel(c: Context, v: String) {
        try {
            secure(c).edit().putString(KEY_STT_MODEL, v).apply()
        } catch (_: Exception) {
        }
    }

    fun getCorrModel(c: Context): String = try {
        secure(c).getString(KEY_CORR_MODEL, DEFAULT_CORR_MODEL) ?: DEFAULT_CORR_MODEL
    } catch (_: Exception) {
        DEFAULT_CORR_MODEL
    }

    fun setCorrModel(c: Context, v: String) {
        try {
            secure(c).edit().putString(KEY_CORR_MODEL, v).apply()
        } catch (_: Exception) {
        }
    }

    fun getProvider(c: Context): String = try {
        secure(c).getString(KEY_PROVIDER, "") ?: ""
    } catch (_: Exception) {
        ""
    }

    fun setProvider(c: Context, v: String) {
        try {
            secure(c).edit().putString(KEY_PROVIDER, v).apply()
        } catch (_: Exception) {
        }
    }

    fun isSkipCorrection(c: Context): Boolean = try {
        secure(c).getBoolean(KEY_SKIP_CORRECTION, false)
    } catch (_: Exception) {
        false
    }

    fun setSkipCorrection(c: Context, v: Boolean) {
        try {
            secure(c).edit().putBoolean(KEY_SKIP_CORRECTION, v).apply()
        } catch (_: Exception) {
        }
    }

    fun getXRatio(c: Context): Float = plain(c).getFloat(KEY_X_RATIO, 0.85f)
    fun setXRatio(c: Context, v: Float) = plain(c).edit().putFloat(KEY_X_RATIO, v).apply()
    fun getYRatio(c: Context): Float = plain(c).getFloat(KEY_Y_RATIO, 0.7f)
    fun setYRatio(c: Context, v: Float) = plain(c).edit().putFloat(KEY_Y_RATIO, v).apply()

    fun clearPosition(c: Context) {
        plain(c).edit().remove(KEY_X_RATIO).remove(KEY_Y_RATIO).apply()
    }

    fun getButtonImagePath(c: Context): String? =
        plain(c).getString(KEY_BTN_IMAGE, null)

    fun setButtonImagePath(c: Context, path: String?) {
        val e = plain(c).edit()
        if (path == null) e.remove(KEY_BTN_IMAGE) else e.putString(KEY_BTN_IMAGE, path)
        e.apply()
    }
}
