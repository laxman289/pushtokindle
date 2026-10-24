package com.morningdigest.kindle.model

import java.io.File

data class XPost(
    val id: String,
    val authorName: String,
    val authorHandle: String,
    val timestamp: String,
    val text: String,
    val metricsSummary: String = "",
    val url: String = ""
)

data class ManoramaArticle(
    val title: String,
    val category: String,
    val url: String,
    val summaryOrBody: String,
    val publishedTime: String = ""
)

data class CuratedXItem(
    val headline: String,
    val authorHandle: String,
    val summary: String,
    val originalText: String = ""
)

data class CuratedNewsItem(
    val headline: String,
    val category: String,
    val bodyMalayalam: String
)

data class CuratedDigest(
    val generatedDateLabel: String,
    val xHighlightsSummary: String,
    val xItems: List<CuratedXItem>,
    val manoramaItems: List<CuratedNewsItem>
)

data class AppSettings(
    val geminiApiKey: String = "",
    val kindleEmail: String = "",
    val smtpEmail: String = "",
    val smtpPassword: String = "",
    val smtpHost: String = "smtp.gmail.com",
    val smtpPort: Int = 587,
    val convertPdfOnKindle: Boolean = false,
    val xScrollIterations: Int = 6,
    val maxManoramaArticles: Int = 12,
    val bodyFontSizePt: Float = 13.5f,
    val scheduleEnabled: Boolean = false,
    val scheduleHour: Int = 6,
    val scheduleMinute: Int = 0
)

sealed class DigestRunState {
    data object Idle : DigestRunState()
    data class Running(val stepMessage: String, val progressFraction: Float) : DigestRunState()
    data class Success(
        val pdfFile: File,
        val xCount: Int,
        val newsCount: Int,
        val emailedToKindle: Boolean,
        val message: String
    ) : DigestRunState()
    data class Error(val errorMessage: String) : DigestRunState()
}
