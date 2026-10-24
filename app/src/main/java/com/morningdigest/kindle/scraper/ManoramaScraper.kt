package com.morningdigest.kindle.scraper

import com.morningdigest.kindle.model.ManoramaArticle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class ManoramaScraper {

    companion object {
        private const val BASE_URL = "https://www.manoramaonline.com"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }

    suspend fun fetchTopNews(
        maxArticles: Int = 12,
        onProgress: (String) -> Unit = {}
    ): List<ManoramaArticle> = withContext(Dispatchers.IO) {
        onProgress("Fetching Manorama Online (manoramaonline.com) homepage...")

        val doc = Jsoup.connect(BASE_URL)
            .userAgent(USER_AGENT)
            .timeout(20_000)
            .get()

        val candidates = mutableListOf<Triple<String, String, String>>() // (title, category, url)
        val seenUrls = mutableSetOf<String>()

        // Primary selector verified on manoramaonline.com: a.cmp-story-list__title-link
        val storyLinks = doc.select("a.cmp-story-list__title-link, h1 a[href], h2 a[href], h3 a[href]")
        for (link in storyLinks) {
            val rawHref = link.attr("href").trim()
            if (rawHref.isBlank() || !rawHref.contains(".html")) continue
            if (rawHref.contains("/videos/") || rawHref.contains("/podcast") || rawHref.contains("/obituary")) continue

            val fullUrl = when {
                rawHref.startsWith("http") -> rawHref
                rawHref.startsWith("/") -> "$BASE_URL$rawHref"
                else -> "$BASE_URL/$rawHref"
            }
            if (!seenUrls.add(fullUrl)) continue

            val title = (link.attr("title").ifBlank { link.text() }).trim()
            if (title.length < 10) continue

            // Try to find category pretitle inside the same story item card
            val itemParent = link.parents().firstOrNull {
                it.hasClass("cmp-story-list__item") || it.hasClass("cmp-story-list__content")
            }
            val category = itemParent
                ?.selectFirst(".cmp-story-list__pretitle")
                ?.text()
                ?.trim()
                ?.ifBlank { inferCategoryFromUrl(fullUrl) }
                ?: inferCategoryFromUrl(fullUrl)

            candidates.add(Triple(title, category, fullUrl))
            if (candidates.size >= maxArticles) break
        }

        onProgress("Found ${candidates.size} top stories on Manorama Online. Fetching full Malayalam article text...")

        coroutineScope {
            candidates.mapIndexed { idx, (title, category, url) ->
                async(Dispatchers.IO) {
                    val body = fetchArticleBodyMalayalam(url, title)
                    onProgress("Fetched Manorama story ${idx + 1}/${candidates.size}...")
                    ManoramaArticle(
                        title = title,
                        category = category,
                        url = url,
                        summaryOrBody = body
                    )
                }
            }.awaitAll()
        }
    }

    private fun fetchArticleBodyMalayalam(url: String, fallbackTitle: String): String {
        return try {
            val doc: Document = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout(15_000)
                .get()

            // 1. Extract from <script type="application/ld+json"> NewsArticle schema (verified on Manorama Online)
            val ldJsonScripts = doc.select("script[type=application/ld+json]")
            for (script in ldJsonScripts) {
                val rawJson = script.data().trim()
                if (rawJson.contains("NewsArticle") && rawJson.contains("articleBody")) {
                    val bodyFromLd = parseNewsArticleJsonLd(rawJson)
                    if (bodyFromLd.isNotBlank()) {
                        return bodyFromLd
                    }
                }
            }

            // 2. Fallback: Extract from article paragraphs + og:description
            val ogDesc = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim() ?: ""
            val paragraphs = doc.select("article p, .article-content p, .cmp-text p")
                .map { it.text().trim() }
                .filter { it.length > 25 && !it.startsWith("Read also", ignoreCase = true) }

            when {
                paragraphs.isNotEmpty() -> paragraphs.take(6).joinToString("\n\n")
                ogDesc.isNotBlank() -> ogDesc
                else -> fallbackTitle
            }
        } catch (e: Exception) {
            fallbackTitle
        }
    }

    private fun parseNewsArticleJsonLd(rawJson: String): String {
        return try {
            val obj = if (rawJson.startsWith("[")) {
                val arr = JSONArray(rawJson)
                (0 until arr.length())
                    .map { arr.getJSONObject(it) }
                    .firstOrNull { it.optString("@type") == "NewsArticle" }
            } else {
                JSONObject(rawJson)
            } ?: return ""

            val articleBody = obj.optString("articleBody", "").trim()
            val description = obj.optString("description", "").trim()
            when {
                articleBody.length > 40 -> articleBody
                description.isNotBlank() -> description
                else -> ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun inferCategoryFromUrl(url: String): String {
        return when {
            url.contains("/latest-news/") -> "പ്രധാന വാർത്തകൾ (Latest)"
            url.contains("/kerala/") -> "കേരളം (Kerala)"
            url.contains("/india/") -> "ഇന്ത്യ (India)"
            url.contains("/world/") -> "ലോകം (World)"
            url.contains("/premium/") -> "മനോരമ പ്രീമിയം (Premium)"
            url.contains("/sports/") -> "കായികം (Sports)"
            url.contains("/business/") || url.contains("/fasttrack/") -> "ബിസിനസ് (Business)"
            else -> "മനോരമ വാർത്തകൾ"
        }
    }
}
