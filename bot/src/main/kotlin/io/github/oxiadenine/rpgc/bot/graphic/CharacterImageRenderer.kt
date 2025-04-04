package io.github.oxiadenine.rpgc.bot.graphic

import com.openhtmltopdf.java2d.api.BufferedImagePageProcessor
import com.openhtmltopdf.java2d.api.Java2DRendererBuilder
import io.github.oxiadenine.rpgc.common.normalize
import io.github.oxiadenine.rpgc.common.repository.Character
import io.github.oxiadenine.rpgc.common.repository.CharacterImage
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory

class CharacterImageRenderer(private val templatePath: String) {
    fun render(character: Character, width: Int): CharacterImage {
        val templateFile = File(templatePath)

        val document = Jsoup.parse(templateFile)

        document.select("#character-name")[0].appendText(character.name.value)
        document.select("#game-name")[0].appendText(character.game!!.name.value)

        val contentDocument = Jsoup.parse(character.content.value)

        document.select("div.row")[0].appendChildren(contentDocument.select("div.column"))

        if (character.isRanking) {
            document.select("#character-content-image")[0]
                .attr("src", "file://${character.content.imageFilePath}")
        }

        document.select("style")[0]
            .appendText(contentDocument.select("style")[0].html())

        document.head().select("style")[0].appendText("""
            @page {
              size: ${width}px 1px;
              margin: 0;
            }
        """.trimIndent())

        document.outputSettings().syntax(Document.OutputSettings.Syntax.xml)

        val xhtmlDocument = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(document.html().toByteArray().inputStream())
        xhtmlDocument.documentURI = templateFile.toURI().toString()

        val rendererBuilder = Java2DRendererBuilder()
        val imagePageProcessor = BufferedImagePageProcessor(BufferedImage.TYPE_INT_RGB, 1.0)

        rendererBuilder.withW3cDocument(xhtmlDocument, xhtmlDocument.baseURI)
        rendererBuilder.useFastMode()
        rendererBuilder.useEnvironmentFonts(true)
        rendererBuilder.toSinglePage(imagePageProcessor)
        rendererBuilder.runFirstPage()

        val imageType = "png"

        val imageName = character.name.value.normalize()
            .replace("[^a-zA-Z0-9 ]".toRegex(), "")
            .replace(" ", "-")
            .lowercase()

        val imageBytes = ByteArrayOutputStream().use { outputStream ->
            ImageIO.write(imagePageProcessor.pageImages[0], imageType, outputStream)

            outputStream.toByteArray()
        }

        return CharacterImage(imageName, imageBytes, imageType, character.id)
    }
}