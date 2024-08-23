package io.github.oxiadenine.rpgcbot.network

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import kotlinx.serialization.Serializable

class TelegraphApi(private val httpClient: HttpClient) {
    companion object {
        const val URL = "https://telegra.ph"
    }

    @Serializable
    data class UploadImageResponse(val src: String)

    suspend fun uploadImage(imageBytes: ByteArray, fileName: String): String {
        val response = httpClient.submitFormWithBinaryData(
            url = "$URL/upload",
            formData = formData {
                append("file", imageBytes, Headers.build {
                    append(HttpHeaders.ContentType, "image/png")
                    append(HttpHeaders.ContentDisposition, "filename=\"${fileName}\"")
                })
            }
        ).body<List<UploadImageResponse>>()[0]

        return "$URL${response.src}"
    }
}