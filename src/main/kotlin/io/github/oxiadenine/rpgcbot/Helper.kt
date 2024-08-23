package io.github.oxiadenine.rpgcbot

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.TelegramFile
import io.github.oxiadenine.rpgcbot.repository.Character
import org.jsoup.Jsoup
import org.w3c.dom.Document
import org.xhtmlrenderer.swing.Java2DRenderer
import org.xhtmlrenderer.util.FSImageWriter
import org.xml.sax.InputSource
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.text.Normalizer
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.createTempFile

fun String.normalize() = Normalizer.normalize(this, Normalizer.Form.NFKD)
    .replace("\\p{M}".toRegex(), "")

fun Character.Name.toCommandName() = this.value
    .normalize()
    .replace("[^a-zA-Z0-9]".toRegex(), "")
    .lowercase()

fun Character.Name.toFileName() = this.value
    .normalize()
    .replace("[^a-zA-Z0-9 ]".toRegex(), "")
    .replace(" ", "-")
    .lowercase()

fun String.toHTMLDocument(): org.jsoup.nodes.Document = Jsoup.parse(this)

fun org.jsoup.nodes.Document.toXHTMLDocument(): Document {
    this.outputSettings().syntax(org.jsoup.nodes.Document.OutputSettings.Syntax.xml)

    return DocumentBuilderFactory.newDefaultInstance().newDocumentBuilder().parse(InputSource(StringReader(this.html())))
}

fun Document.renderToImage(width: Int, height: Int? = null): ByteArray = ByteArrayOutputStream().use { outputStream ->
    val graphicRenderer = if (height != null) {
        Java2DRenderer(this, width, height)
    } else Java2DRenderer(this, width)

    FSImageWriter().write(graphicRenderer.image, outputStream)

    outputStream.toByteArray()
}

fun Bot.getAndCreateTempFile(fileId: String) = this.getFile(fileId).let { result ->
    val telegramFile = result.first?.let { response ->
        if (response.isSuccessful) {
            response.body()!!.result!!.filePath!!
        } else error(response.message())
    }?.let { filePath ->
        this.downloadFile(filePath).first?.let { response ->
            if (response.isSuccessful) {
                TelegramFile.ByByteArray(
                    fileBytes = response.body()!!.bytes(),
                    filename = filePath.substringAfterLast("/")
                )
            } else error(response.message())
        } ?: throw result.second!!
    } ?: throw result.second!!

    val fileExtension = telegramFile.filename!!.substringAfter(".")

    val tempFile = createTempFile(suffix = ".$fileExtension").toFile()
    tempFile.writeBytes(telegramFile.fileBytes)

    tempFile!!
}