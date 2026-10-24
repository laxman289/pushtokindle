package com.morningdigest.kindle.email

import com.morningdigest.kindle.model.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties
import javax.activation.DataHandler
import javax.activation.FileDataSource
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

class KindleEmailSender {

    suspend fun sendPdfToKindle(
        settings: AppSettings,
        pdfFile: File
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(settings.kindleEmail.isNotBlank()) { "Kindle email (@kindle.com) is empty." }
            require(settings.smtpEmail.isNotBlank()) { "Sender SMTP email is empty." }
            require(settings.smtpPassword.isNotBlank()) { "Sender SMTP App Password is empty." }

            val props = Properties().apply {
                put("mail.smtp.auth", "true")
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.host", settings.smtpHost.ifBlank { "smtp.gmail.com" })
                put("mail.smtp.port", settings.smtpPort.toString())
                put("mail.smtp.ssl.trust", settings.smtpHost.ifBlank { "smtp.gmail.com" })
                put("mail.smtp.connectiontimeout", "20000")
                put("mail.smtp.timeout", "30000")
            }

            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    return PasswordAuthentication(
                        settings.smtpEmail.trim(),
                        settings.smtpPassword.trim()
                    )
                }
            })

            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(settings.smtpEmail.trim()))
                setRecipients(
                    Message.RecipientType.TO,
                    InternetAddress.parse(settings.kindleEmail.trim())
                )
                // If convertPdfOnKindle is enabled, Amazon converts PDF to reflowable format;
                // otherwise it keeps our custom 360x480 Kindle Paperwhite native PDF layout.
                subject = if (settings.convertPdfOnKindle) "Convert" else "Morning Digest"

                val textPart = MimeBodyPart().apply {
                    setText("Attached is your Kindle Morning Digest (X Following + Manorama Online).")
                }

                val attachmentPart = MimeBodyPart().apply {
                    dataHandler = DataHandler(FileDataSource(pdfFile))
                    fileName = pdfFile.name
                }

                val multipart = MimeMultipart().apply {
                    addBodyPart(textPart)
                    addBodyPart(attachmentPart)
                }

                setContent(multipart)
            }

            Transport.send(message)
        }
    }
}
