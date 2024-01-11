package io.github.oxiadenine.rpgcbot.network

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray

class TelegraphApi(private val httpClient: HttpClient) {
    @Serializable
    data class Page(
        val path: String,
        val url: String,
        val title: String,
        val description: String,
        @SerialName("author_name") val authorName: String? = null,
        @SerialName("author_url") val authorUrl: String? = null,
        @SerialName("image_url") val imageUrl: String? = null,
        val content: JsonArray? = null,
        val views: Int,
        @SerialName("can_edit") val canEdit: Boolean? = null
    )

    @Serializable
    data class PageList(@SerialName("total_count") val totalCount: Int, val pages: Array<Page>)

    @Serializable
    data class Node(val tag: String, val children: List<NodeElement>? = null)

    @Serializable
    data class NodeElement(val tag: String, val children: List<String>? = null)

    @Serializable
    data class CreatePage(
        @SerialName("access_token") val accessToken: String,
        val title: String,
        @SerialName("author_name") val authorName: String? = null,
        @SerialName("author_url") val authorUrl: String? = null,
        val content: String,
        @SerialName("return_content") val returnContent: Boolean = false
    )

    @Serializable
    data class EditPage(
        @SerialName("access_token") val accessToken: String,
        val title: String,
        val content: String,
        @SerialName("author_name") val authorName: String? = null,
        @SerialName("author_url") val authorUrl: String? = null,
        @SerialName("return_content") val returnContent: Boolean = false
    )

    @Serializable
    data class GetPage(@SerialName("return_content") val returnContent: Boolean = true)

    @Serializable
    data class GetPageList(
        @SerialName("access_token") val accessToken: String,
        val offset: Int = 0,
        val limit: Int = 50
    )

    @Serializable
    data class PageResponse(val ok: Boolean, val result: Page?, val error: String?)

    @Serializable
    data class PageListResponse(val ok: Boolean, val result: PageList?, val error: String?)

    suspend fun createPage(createPage: CreatePage) = runCatching {
        handlePageResponse(httpClient.post("/createPage") { setBody(createPage) })
    }

    suspend fun editPage(path: String, editPage: EditPage) = runCatching {
        handlePageResponse(httpClient.post("/editPage/$path") { setBody(editPage) })
    }

    suspend fun getPage(path:String, getPage: GetPage) = runCatching {
        handlePageResponse(httpClient.post("/getPage/$path") { setBody(getPage) })
    }

    suspend fun getPageList(getPageList: GetPageList) = runCatching {
        handlePageListResponse(httpClient.post("/getPageList") { setBody(getPageList) })
    }

    private suspend fun handlePageResponse(httpResponse: HttpResponse): Page {
        val pageResponse = httpResponse.body<PageResponse>()

        if (!pageResponse.ok) {
            error(pageResponse.error!!)
        }

        return pageResponse.result!!
    }

    private suspend fun handlePageListResponse(httpResponse: HttpResponse): PageList {
        val pageListResponse = httpResponse.body<PageListResponse>()

        if (!pageListResponse.ok) {
            error(pageListResponse.error!!)
        }

        return pageListResponse.result!!
    }
}