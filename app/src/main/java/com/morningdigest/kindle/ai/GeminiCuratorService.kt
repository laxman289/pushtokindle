package com.morningdigest.kindle.ai

import com.morningdigest.kindle.model.CuratedDigest
import com.morningdigest.kindle.model.CuratedNewsItem
import com.morningdigest.kindle.model.CuratedXItem
import com.morningdigest.kindle.model.ManoramaArticle
import com.morningdigest.kindle.model.XPost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class GeminiCuratorService {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun curateMorningDigest(
        apiKey: String,
        xPosts: List<XPost>,
        manoramaArticles: List<ManoramaArticle>,
        onProgress: (String) -> Unit = {}
    ): CuratedDigest = withContext(Dispatchers.IO) {
        val dateLabel = SimpleDateFormat("EEEE, dd MMM yyyy", Locale.ENGLISH).format(Date())

        val (xSummary, curatedX) = if (apiKey.isNotBlank() && xPosts.isNotEmpty()) {
            onProgress("Using Gemini AI to filter important posts from your X Following tab...")
            curateXWithGemini(apiKey, xPosts) ?: fallbackX(xPosts)
        } else {
            fallbackX(xPosts)
        }

        val curatedNews = if (apiKey.isNotBlank() && manoramaArticles.isNotEmpty()) {
            onProgress("Using Gemini AI to summarize Manorama Online top news in Malayalam...")
            curateManoramaWithGemini(apiKey, manoramaArticles) ?: fallbackManorama(manoramaArticles)
        } else {
            fallbackManorama(manoramaArticles)
        }

        CuratedDigest(
            generatedDateLabel = dateLabel,
            xHighlightsSummary = xSummary,
            xItems = curatedX,
            manoramaItems = curatedNews
        )
    }

    private fun curateXWithGemini(
        apiKey: String,
        xPosts: List<XPost>
    ): Pair<String, List<CuratedXItem>>? {
        return try {
            val postsJson = JSONArray()
            xPosts.take(45).forEachIndexed { idx, post ->
                postsJson.put(
                    JSONObject().apply {
                        put("index", idx + 1)
                        put("author", "${post.authorName} (${post.authorHandle})")
                        put("text", post.text)
                        put("metrics", post.metricsSummary)
                    }
                )
            }

            val prompt = """
                You are curating a calm, high-signal morning Kindle newspaper from the user's X (Twitter) 'Following' tab.
                Filter out ads, giveaways, low-effort one-word replies, and noise.
                Select the most important, informative, or insightful posts/threads (up to 15 items).
                Return ONLY valid JSON with this exact structure:
                {
                  "overview": "2-3 sentence executive summary of the biggest themes in the user's Following feed this morning.",
                  "items": [
                    {
                      "headline": "Short, clear 5-10 word headline for the post",
                      "authorHandle": "@handle (Author Name)",
                      "summary": "Clear, readable summary or full post text preserving key numbers, links, and insights."
                    }
                  ]
                }

                Posts from Following tab:
                $postsJson
            """.trimIndent()

            val responseJsonStr = callGeminiJson(apiKey, prompt) ?: return null
            val root = JSONObject(responseJsonStr)
            val overview = root.optString("overview", "Top curated updates from your X Following tab.")
            val itemsArr = root.optJSONArray("items") ?: return null

            val list = mutableListOf<CuratedXItem>()
            for (i in 0 until itemsArr.length()) {
                val item = itemsArr.getJSONObject(i)
                list.add(
                    CuratedXItem(
                        headline = item.optString("headline", "X Update"),
                        authorHandle = item.optString("authorHandle", ""),
                        summary = item.optString("summary", "")
                    )
                )
            }
            if (list.isEmpty()) null else (overview to list)
        } catch (e: Exception) {
            null
        }
    }

    private fun curateManoramaWithGemini(
        apiKey: String,
        articles: List<ManoramaArticle>
    ): List<CuratedNewsItem>? {
        return try {
            val articlesJson = JSONArray()
            articles.forEachIndexed { idx, art ->
                articlesJson.put(
                    JSONObject().apply {
                        put("index", idx + 1)
                        put("title", art.title)
                        put("category", art.category)
                        put("body", art.summaryOrBody.take(1500))
                    }
                )
            }

            val prompt = """
                You are preparing the Malayalam Morning News section from Manorama Online (മനോരമ ഓൺലൈൻ) for a Kindle Paperwhite reader.
                IMPORTANT: Keep ALL headlines, categories, and summaries strictly in Malayalam script (മലയാളം). Do NOT translate into English.
                For each news story, provide the original or refined Malayalam headline, category, and a well-structured, comprehensive Malayalam summary (4-6 clear sentences covering all key facts, names, and locations so the reader gets the full story without opening a browser).
                Return ONLY valid JSON with this exact structure:
                {
                  "stories": [
                    {
                      "headline": "മലയാളം തലക്കെട്ട്",
                      "category": "വിഭാഗം",
                      "bodyMalayalam": "വാർത്തയുടെ പൂർണ്ണ രൂപത്തിലുള്ള വ്യക്തമായ മലയാളം സംഗ്രഹം..."
                    }
                  ]
                }

                Manorama Online Articles:
                $articlesJson
            """.trimIndent()

            val responseJsonStr = callGeminiJson(apiKey, prompt) ?: return null
            val root = JSONObject(responseJsonStr)
            val arr = root.optJSONArray("stories") ?: return null

            val list = mutableListOf<CuratedNewsItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    CuratedNewsItem(
                        headline = obj.optString("headline", ""),
                        category = obj.optString("category", "മനോരമ വാർത്തകൾ"),
                        bodyMalayalam = obj.optString("bodyMalayalam", "")
                    )
                )
            }
            if (list.isEmpty()) null else list
        } catch (e: Exception) {
            null
        }
    }

    private fun callGeminiJson(apiKey: String, prompt: String): String? {
        val url =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=${apiKey.trim()}"

        val payload = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", prompt))
                    )
                )
            )
            put(
                "generationConfig",
                JSONObject().apply {
                    put("responseMimeType", "application/json")
                    put("temperature", 0.2)
                }
            )
        }

        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val bodyStr = response.body?.string() ?: return null
            val json = JSONObject(bodyStr)
            val candidates = json.optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null
            val content = candidates.getJSONObject(0).optJSONObject("content") ?: return null
            val parts = content.optJSONArray("parts") ?: return null
            if (parts.length() == 0) return null
            return parts.getJSONObject(0).optString("text", null)
        }
    }

    private fun fallbackX(xPosts: List<XPost>): Pair<String, List<CuratedXItem>> {
        if (xPosts.isEmpty()) {
            return "No X posts were collected. Make sure you have logged into X inside the app using the 'Log in to X' button." to emptyList()
        }
        val items = xPosts.take(20).map { post ->
            val firstLine = post.text.lineSequence().firstOrNull()?.take(65) ?: "Update from ${post.authorName}"
            CuratedXItem(
                headline = firstLine,
                authorHandle = "${post.authorName} ${post.authorHandle}".trim(),
                summary = post.text
            )
        }
        return "Showing ${items.size} recent posts from your X Following tab." to items
    }

    private fun fallbackManorama(articles: List<ManoramaArticle>): List<CuratedNewsItem> {
        return articles.map { art ->
            CuratedNewsItem(
                headline = art.title,
                category = art.category,
                bodyMalayalam = art.summaryOrBody
            )
        }
    }
}
