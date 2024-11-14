package io.github.oxiadenine.rpgcbot

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.TelegramFile
import com.openhtmltopdf.java2d.api.BufferedImagePageProcessor
import com.openhtmltopdf.java2d.api.Java2DRendererBuilder
import io.github.oxiadenine.rpgcbot.repository.Character
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.Normalizer
import javax.imageio.ImageIO
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

fun Character.renderToImage(templatePath: String, width: Int): ByteArray = ByteArrayOutputStream().use { outputStream ->
    val characterTemplateFile = File(templatePath)

    val characterDocument = Jsoup.parse(characterTemplateFile)

    characterDocument.select("#character-name")[0].appendText(this.name.value)
    characterDocument.select("#game-name")[0].appendText(this.game!!.name.value)

    val characterContentDocument = Jsoup.parse(this.content.value)

    characterDocument.select("div.row")[0]
        .appendChildren(characterContentDocument.select("div.column"))

    if (this.isRanking) {
        characterDocument.select("#character-content-image")[0]
            .attr("src", "file://${this.content.imageFilePath}")
    }

    characterDocument.select("style")[0]
        .appendText(characterContentDocument.select("style")[0].html())

    characterDocument.head().select("style")[0].appendText("""
        @page {
          size: ${width}px 1px;
          margin: 0;
        }
    """.trimIndent())

    characterDocument.outputSettings().syntax(Document.OutputSettings.Syntax.xml)

    val xhtmlDocument = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(characterDocument.html().toByteArray().inputStream())
    xhtmlDocument.documentURI = characterTemplateFile.toURI().toString()

    val rendererBuilder = Java2DRendererBuilder()
    val imagePageProcessor = BufferedImagePageProcessor(BufferedImage.TYPE_INT_RGB, 1.0)

    rendererBuilder.withW3cDocument(xhtmlDocument, xhtmlDocument.baseURI)
    rendererBuilder.useFastMode()
    rendererBuilder.useEnvironmentFonts(true)
    rendererBuilder.toSinglePage(imagePageProcessor)
    rendererBuilder.runFirstPage()

    ImageIO.write(imagePageProcessor.pageImages[0], "png", outputStream)

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

fun Bot.sendDocumentAndGetFileId(chatId: ChatId, document: TelegramFile) = this.sendDocument(chatId, document).let { result ->
    result.first?.let { response ->
        if (response.isSuccessful) {
            response.body()!!.result!!.document!!.fileId
        } else error(response.message())
    } ?: throw result.second!!
}