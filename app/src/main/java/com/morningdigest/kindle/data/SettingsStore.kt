package com.morningdigest.kindle.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.morningdigest.kindle.model.AppSettings

class SettingsStore(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "kindle_digest_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        context.getSharedPreferences("kindle_digest_fallback_prefs", Context.MODE_PRIVATE)
    }

    fun load(): AppSettings {
        return AppSettings(
            geminiApiKey = prefs.getString("gemini_api_key", "") ?: "",
            kindleEmail = prefs.getString("kindle_email", "") ?: "",
            smtpEmail = prefs.getString("smtp_email", "") ?: "",
            smtpPassword = prefs.getString("smtp_password", "") ?: "",
            smtpHost = prefs.getString("smtp_host", "smtp.gmail.com") ?: "smtp.gmail.com",
            smtpPort = prefs.getInt("smtp_port", 587),
            convertPdfOnKindle = prefs.getBoolean("convert_pdf_on_kindle", false),
            xScrollIterations = prefs.getInt("x_scroll_iterations", 6),
            maxManoramaArticles = prefs.getInt("max_manorama_articles", 12),
            bodyFontSizePt = prefs.getFloat("body_font_size_pt", 13.5f),
            scheduleEnabled = prefs.getBoolean("schedule_enabled", false),
            scheduleHour = prefs.getInt("schedule_hour", 6),
            scheduleMinute = prefs.getInt("schedule_minute", 0)
        )
    }

    fun save(settings: AppSettings) {
        prefs.edit()
            .putString("gemini_api_key", settings.geminiApiKey.trim())
            .putString("kindle_email", settings.kindleEmail.trim())
            .putString("smtp_email", settings.smtpEmail.trim())
            .putString("smtp_password", settings.smtpPassword.trim())
            .putString("smtp_host", settings.smtpHost.trim())
            .putInt("smtp_port", settings.smtpPort)
            .putBoolean("convert_pdf_on_kindle", settings.convertPdfOnKindle)
            .putInt("x_scroll_iterations", settings.xScrollIterations.coerceIn(2, 15))
            .putInt("max_manorama_articles", settings.maxManoramaArticles.coerceIn(5, 25))
            .putFloat("body_font_size_pt", settings.bodyFontSizePt.coerceIn(11f, 20f))
            .putBoolean("schedule_enabled", settings.scheduleEnabled)
            .putInt("schedule_hour", settings.scheduleHour.coerceIn(0, 23))
            .putInt("schedule_minute", settings.scheduleMinute.coerceIn(0, 59))
            .apply()
    }
}
