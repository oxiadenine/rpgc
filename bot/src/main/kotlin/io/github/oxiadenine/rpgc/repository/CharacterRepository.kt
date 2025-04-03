package io.github.oxiadenine.rpgc.repository

import com.openhtmltopdf.java2d.api.BufferedImagePageProcessor
import com.openhtmltopdf.java2d.api.Java2DRendererBuilder
import io.github.oxiadenine.rpgc.CharacterTable
import io.github.oxiadenine.rpgc.Database
import io.github.oxiadenine.rpgc.normalize
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory

class Character(
    val id: UUID = UUID.randomUUID(),
    val name: Name = Name(),
    val content: Content = Content(),
    val isRanking: Boolean = false,
    val game: Game? = null
) {
    sealed class NameException : Exception() {
        class Blank : NameException()
        class Length : NameException()
        class Invalid : NameException()
    }

    class Name(value: String? = null) {
        val value: String = value?.run {
            if (value.isBlank()) {
                throw NameException.Blank()
            }

            if (value.length > 64) {
                throw NameException.Length()
            }

            if (!value.matches("^(.+ ?)+$".toRegex())) {
                throw NameException.Invalid()
            }

            value
        } ?: ""
    }

    sealed class ContentException : Exception() {
        class Blank : ContentException()
        class Length : ContentException()
    }

    class Content(value: String? = null, val imageFilePath: String? = null) {
        val value = value?.run {
            if (value.isBlank()) {
                throw ContentException.Blank()
            }

            if (value.length > 64000) {
                throw ContentException.Length()
            }

            value
        } ?: ""
    }
}

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

class CharacterRepository(private val database: Database) {
    suspend fun create(character: Character) = database.transaction {
        CharacterTable.insert { statement ->
            statement[id] = character.id
            statement[name] = character.name.value
            statement[content] = character.content.value
            statement[isRanking] = character.isRanking
            statement[gameId] = character.game!!.id
        }

        Unit
    }

    suspend fun read() = database.transaction {
        CharacterTable.selectAll().map { record ->
            Character(
                record[CharacterTable.id],
                Character.Name(record[CharacterTable.name]),
                Character.Content(record[CharacterTable.content]),
                record[CharacterTable.isRanking],
                Game(record[CharacterTable.gameId])
            )
        }
    }

    suspend fun read(gameId: UUID) = database.transaction {
        CharacterTable.selectAll().where { CharacterTable.gameId eq gameId }.map { record ->
            Character(
                record[CharacterTable.id],
                Character.Name(record[CharacterTable.name]),
                Character.Content(record[CharacterTable.content]),
                record[CharacterTable.isRanking],
                Game(record[CharacterTable.gameId])
            )
        }
    }

    suspend fun update(character: Character) = database.transaction {
        CharacterTable.update({ CharacterTable.id eq character.id }) { statement ->
            statement[content] = character.content.value
        }

        Unit
    }
}