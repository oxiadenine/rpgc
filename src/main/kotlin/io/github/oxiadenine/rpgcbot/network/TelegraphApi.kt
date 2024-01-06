package io.github.oxiadenine.rpgcbot.network

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.statement.*
import io.ktor.resources.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
        val content: List<String>? = null,
        val views: Int,
        @SerialName("can_edit") val canEdit: Boolean? = null
    )

    @Serializable
    data class PageList(@SerialName("total_count") val totalCount: Int, val pages: Array<Page>)

    @Resource("/createPage")
    class CreatePage(
        @SerialName("access_token") val accessToken: String,
        val title: String,
        @SerialName("author_name") val authorName: String? = null,
        @SerialName("author_url") val authorUrl: String? = null,
        val content: String,
        @SerialName("return_content") val returnContent: Boolean = false
    )

    @Resource("/editPage/{path}")
    class EditPage(
        val path: String,
        @SerialName("access_token") val accessToken: String,
        val title: String,
        val content: String,
        @SerialName("author_name") val authorName: String? = null,
        @SerialName("author_url") val authorUrl: String? = null,
        @SerialName("return_content") val returnContent: Boolean = false
    )

    @Resource("/getPage/{path}")
    class GetPage(val path: String, @SerialName("return_content") val returnContent: Boolean = true)

    @Resource("/getPageList")
    class GetPageList(@SerialName("access_token") val accessToken: String, val offset: Int = 0, val limit: Int = 50)

    @Serializable
    data class PageResponse(val ok: Boolean, val result: Page?, val error: String?)

    @Serializable
    data class PageListResponse(val ok: Boolean, val result: PageList?, val error: String?)

    suspend fun createPage(createPage: CreatePage) = runCatching {
        handlePageResponse(httpClient.get(createPage))
    }

    suspend fun editPage(editPage: EditPage) = runCatching {
        handlePageResponse(httpClient.get(editPage))
    }

    suspend fun getPage(getPage: GetPage) = runCatching {
        handlePageResponse(httpClient.get(getPage))
    }

    suspend fun getPageList(getPageList: GetPageList) = runCatching {
        handlePageListResponse(httpClient.get(getPageList))
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