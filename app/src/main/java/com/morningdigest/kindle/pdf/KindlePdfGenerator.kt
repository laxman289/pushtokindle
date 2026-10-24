package com.morningdigest.kindle.pdf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.morningdigest.kindle.model.CuratedDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class KindlePdfGenerator(private val context: Context) {

    companion object {
        // 360 x 480 pt (3:4 aspect ratio matched to Kindle Paperwhite 6" / 6.8" screen)
        // At this viewport size, a 13.5pt font renders large and crisp with zero zooming required.
        private const val PAGE_WIDTH = 360
        private const val PAGE_HEIGHT = 480
        private const val MARGIN_H = 18f
        private const val MARGIN_TOP = 20f
        private const val MARGIN_BOTTOM = 26f
        private val CONTENT_WIDTH = (PAGE_WIDTH - (MARGIN_H * 2)).toInt()
    }

    suspend fun generateKindlePdf(
        digest: CuratedDigest,
        bodyFontSizePt: Float = 13.5f
    ): File = withContext(Dispatchers.IO) {
        val pdfDocument = PdfDocument()

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = bodyFontSizePt + 5.5f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }

        val sectionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = bodyFontSizePt + 3.0f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }

        val itemTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = bodyFontSizePt + 1.2f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }

        val metaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = bodyFontSizePt - 1.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC)
        }

        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = bodyFontSizePt
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        }

        val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 9.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }

        val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            strokeWidth = 1.2f
        }

        val thinRulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            strokeWidth = 0.6f
        }

        var pageNumber = 1
        var currentPage = startPage(pdfDocument, pageNumber)
        var canvas = currentPage.canvas
        var cursorY = MARGIN_TOP

        fun finishCurrentPageAndStartNew() {
            drawFooter(canvas, pageNumber, digest.generatedDateLabel, footerPaint, thinRulePaint)
            pdfDocument.finishPage(currentPage)
            pageNumber++
            currentPage = startPage(pdfDocument, pageNumber)
            canvas = currentPage.canvas
            cursorY = MARGIN_TOP
        }

        fun ensureSpace(requiredHeight: Float) {
            if (cursorY + requiredHeight > PAGE_HEIGHT - MARGIN_BOTTOM) {
                finishCurrentPageAndStartNew()
            }
        }

        // Paragraph-aware text renderer that wraps and paginates long text cleanly
        fun drawBlockText(
            text: String,
            paint: TextPaint,
            lineSpacingMult: Float = 1.28f,
            spacingAfter: Float = 8f
        ) {
            val paragraphs = text.split("\n")
            for (para in paragraphs) {
                val trimmed = para.trim()
                if (trimmed.isEmpty()) {
                    cursorY += 4f
                    continue
                }
                val layout = buildStaticLayout(trimmed, paint, CONTENT_WIDTH, lineSpacingMult)
                val height = layout.height.toFloat()

                if (cursorY + height <= PAGE_HEIGHT - MARGIN_BOTTOM) {
                    canvas.save()
                    canvas.translate(MARGIN_H, cursorY)
                    layout.draw(canvas)
                    canvas.restore()
                    cursorY += height + 4f
                } else {
                    // Render line-by-line across page boundary if needed
                    for (lineIdx in 0 until layout.lineCount) {
                        val lineTop = layout.getLineTop(lineIdx).toFloat()
                        val lineBottom = layout.getLineBottom(lineIdx).toFloat()
                        val lineHeight = lineBottom - lineTop
                        ensureSpace(lineHeight + 2f)

                        val lineStart = layout.getLineStart(lineIdx)
                        val lineEnd = layout.getLineEnd(lineIdx)
                        val lineText = trimmed.substring(lineStart, lineEnd)
                        canvas.drawText(
                            lineText,
                            MARGIN_H,
                            cursorY - paint.ascent(),
                            paint
                        )
                        cursorY += lineHeight
                    }
                    cursorY += 4f
                }
            }
            cursorY += spacingAfter
        }

        // ==================== MASTHEAD (PAGE 1) ====================
        drawBlockText("KINDLE MORNING BRIEF", titlePaint, lineSpacingMult = 1.15f, spacingAfter = 2f)
        drawBlockText(
            "${digest.generatedDateLabel}  •  X Following + Manorama Online",
            metaPaint,
            lineSpacingMult = 1.15f,
            spacingAfter = 6f
        )
        canvas.drawLine(MARGIN_H, cursorY, PAGE_WIDTH - MARGIN_H, cursorY, rulePaint)
        cursorY += 12f

        // ==================== SECTION 1: X (TWITTER) FOLLOWING FEED ====================
        drawBlockText(
            "PART 1: X (FOLLOWING) HIGHLIGHTS",
            sectionPaint,
            lineSpacingMult = 1.2f,
            spacingAfter = 6f
        )

        if (digest.xHighlightsSummary.isNotBlank()) {
            drawBlockText(
                digest.xHighlightsSummary,
                bodyPaint,
                lineSpacingMult = 1.3f,
                spacingAfter = 10f
            )
            canvas.drawLine(MARGIN_H, cursorY, PAGE_WIDTH - MARGIN_H, cursorY, thinRulePaint)
            cursorY += 10f
        }

        digest.xItems.forEachIndexed { index, item ->
            // Keep headline + author together on the same page
            ensureSpace(65f)
            drawBlockText(
                "${index + 1}. ${item.headline}",
                itemTitlePaint,
                lineSpacingMult = 1.22f,
                spacingAfter = 2f
            )
            if (item.authorHandle.isNotBlank()) {
                drawBlockText(
                    item.authorHandle,
                    metaPaint,
                    lineSpacingMult = 1.15f,
                    spacingAfter = 4f
                )
            }
            drawBlockText(
                item.summary,
                bodyPaint,
                lineSpacingMult = 1.32f,
                spacingAfter = 8f
            )
            ensureSpace(12f)
            canvas.drawLine(MARGIN_H, cursorY, PAGE_WIDTH - MARGIN_H, cursorY, thinRulePaint)
            cursorY += 10f
        }

        // ==================== SECTION 2: MANORAMA ONLINE MALAYALAM NEWS (NEW PAGE) ====================
        finishCurrentPageAndStartNew()

        drawBlockText(
            "PART 2: മനോരമ ഓൺലൈൻ — പ്രധാന വാർത്തകൾ",
            sectionPaint,
            lineSpacingMult = 1.25f,
            spacingAfter = 4f
        )
        drawBlockText(
            "Manorama Online Top News (${digest.manoramaItems.size} Stories)",
            metaPaint,
            lineSpacingMult = 1.15f,
            spacingAfter = 6f
        )
        canvas.drawLine(MARGIN_H, cursorY, PAGE_WIDTH - MARGIN_H, cursorY, rulePaint)
        cursorY += 12f

        digest.manoramaItems.forEachIndexed { index, story ->
            ensureSpace(75f)
            if (story.category.isNotBlank()) {
                drawBlockText(
                    "[${story.category}]",
                    metaPaint,
                    lineSpacingMult = 1.15f,
                    spacingAfter = 2f
                )
            }
            drawBlockText(
                "${index + 1}. ${story.headline}",
                itemTitlePaint,
                lineSpacingMult = 1.28f,
                spacingAfter = 5f
            )
            drawBlockText(
                story.bodyMalayalam,
                bodyPaint,
                lineSpacingMult = 1.38f,
                spacingAfter = 8f
            )
            ensureSpace(12f)
            canvas.drawLine(MARGIN_H, cursorY, PAGE_WIDTH - MARGIN_H, cursorY, thinRulePaint)
            cursorY += 10f
        }

        // Finish final page
        drawFooter(canvas, pageNumber, digest.generatedDateLabel, footerPaint, thinRulePaint)
        pdfDocument.finishPage(currentPage)

        val digestsDir = File(context.filesDir, "digests").apply { mkdirs() }
        val fileDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val outputFile = File(digestsDir, "Kindle_Morning_Digest_$fileDate.pdf")

        FileOutputStream(outputFile).use { out ->
            pdfDocument.writeTo(out)
        }
        pdfDocument.close()

        outputFile
    }

    private fun startPage(pdfDocument: PdfDocument, pageNumber: Int): PdfDocument.Page {
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
        val page = pdfDocument.startPage(pageInfo)
        page.canvas.drawColor(Color.WHITE)
        return page
    }

    private fun drawFooter(
        canvas: Canvas,
        pageNumber: Int,
        dateLabel: String,
        footerPaint: TextPaint,
        rulePaint: Paint
    ) {
        val footerLineY = PAGE_HEIGHT - 20f
        canvas.drawLine(MARGIN_H, footerLineY, PAGE_WIDTH - MARGIN_H, footerLineY, rulePaint)
        val footerText = "Page $pageNumber  •  $dateLabel"
        canvas.drawText(footerText, MARGIN_H, PAGE_HEIGHT - 8f, footerPaint)
    }

    private fun buildStaticLayout(
        text: String,
        paint: TextPaint,
        width: Int,
        lineSpacingMult: Float
    ): StaticLayout {
        return StaticLayout.Builder
            .obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, lineSpacingMult)
            .setIncludePad(true)
            .build()
    }
}
