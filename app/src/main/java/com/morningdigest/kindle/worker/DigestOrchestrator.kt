package com.morningdigest.kindle.worker

import android.content.Context
import com.morningdigest.kindle.ai.GeminiCuratorService
import com.morningdigest.kindle.data.SettingsStore
import com.morningdigest.kindle.email.KindleEmailSender
import com.morningdigest.kindle.model.DigestRunState
import com.morningdigest.kindle.pdf.KindlePdfGenerator
import com.morningdigest.kindle.scraper.ManoramaScraper
import com.morningdigest.kindle.scraper.XFeedScraper

class DigestOrchestrator(private val context: Context) {

    private val settingsStore = SettingsStore(context)
    private val xFeedScraper = XFeedScraper(context)
    private val manoramaScraper = ManoramaScraper()
    private val geminiCurator = GeminiCuratorService()
    private val pdfGenerator = KindlePdfGenerator(context)
    private val emailSender = KindleEmailSender()

    suspend fun runFullPipeline(
        onStateUpdate: (DigestRunState) -> Unit = {}
    ): DigestRunState {
        return try {
            val settings = settingsStore.load()

            // Step 1: Scrape X (Following Tab)
            onStateUpdate(
                DigestRunState.Running(
                    stepMessage = "Step 1/4: Opening X (Following tab) in hidden WebView...",
                    progressFraction = 0.15f
                )
            )
            val xPosts = xFeedScraper.scrapeFollowingFeed(
                scrollIterations = settings.xScrollIterations
            ) { msg ->
                onStateUpdate(DigestRunState.Running("Step 1/4: $msg", 0.25f))
            }

            // Step 2: Scrape Manorama Online Malayalam Top News
            onStateUpdate(
                DigestRunState.Running(
                    stepMessage = "Step 2/4: Scraping Manorama Online top Malayalam news...",
                    progressFraction = 0.45f
                )
            )
            val manoramaArticles = manoramaScraper.fetchTopNews(
                maxArticles = settings.maxManoramaArticles
            ) { msg ->
                onStateUpdate(DigestRunState.Running("Step 2/4: $msg", 0.55f))
            }

            // Step 3: Curate with Gemini AI & Render Kindle Paperwhite PDF
            onStateUpdate(
                DigestRunState.Running(
                    stepMessage = "Step 3/4: Curating digest & formatting Kindle Paperwhite PDF...",
                    progressFraction = 0.72f
                )
            )
            val curatedDigest = geminiCurator.curateMorningDigest(
                apiKey = settings.geminiApiKey,
                xPosts = xPosts,
                manoramaArticles = manoramaArticles
            ) { msg ->
                onStateUpdate(DigestRunState.Running("Step 3/4: $msg", 0.78f))
            }

            onStateUpdate(
                DigestRunState.Running(
                    stepMessage = "Step 3/4: Rendering 360x480pt Kindle Paperwhite PDF...",
                    progressFraction = 0.85f
                )
            )
            val pdfFile = pdfGenerator.generateKindlePdf(
                digest = curatedDigest,
                bodyFontSizePt = settings.bodyFontSizePt
            )

            // Step 4: Email to @kindle.com if configured
            val hasEmailConfig = settings.kindleEmail.isNotBlank() &&
                settings.smtpEmail.isNotBlank() &&
                settings.smtpPassword.isNotBlank()

            var emailed = false
            var statusNote = "PDF saved locally (${pdfFile.name})."

            if (hasEmailConfig) {
                onStateUpdate(
                    DigestRunState.Running(
                        stepMessage = "Step 4/4: Sending PDF to ${settings.kindleEmail}...",
                        progressFraction = 0.94f
                    )
                )
                val emailResult = emailSender.sendPdfToKindle(settings, pdfFile)
                emailResult.fold(
                    onSuccess = {
                        emailed = true
                        statusNote = "Sent to ${settings.kindleEmail} and saved locally!"
                    },
                    onFailure = { err ->
                        statusNote = "PDF generated, but email failed: ${err.message}"
                    }
                )
            } else {
                statusNote = "PDF generated! Configure Kindle & SMTP Email below to auto-send to your Kindle."
            }

            val finalState = DigestRunState.Success(
                pdfFile = pdfFile,
                xCount = curatedDigest.xItems.size,
                newsCount = curatedDigest.manoramaItems.size,
                emailedToKindle = emailed,
                message = statusNote
            )
            onStateUpdate(finalState)
            finalState
        } catch (e: Exception) {
            val errState = DigestRunState.Error(e.message ?: "Unexpected error during digest generation.")
            onStateUpdate(errState)
            errState
        }
    }
}
