package com.trackr.core.network.remote.webdav

import com.trackr.core.network.remote.common.NetworkErrors
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.http.contentType
import kotlin.io.encoding.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.IOException

/**
 * 手写 WebDAV 动词层（PROPFIND / MKCOL / PUT / GET / DELETE + Basic 认证），复用应用的共用 Ktor 客户端
 * （`createTrackrHttpClient`，Android=OkHttp / iOS=Darwin），不引入任何新依赖：
 * Ktor 的核心 `request()` 本就支持任意方法名，`HttpMethod("PROPFIND")`、`HttpMethod("MKCOL")` 直接用。
 *
 * 本类只发请求、原样带回状态码和响应文本；状态码含义（MKCOL 405/409=目录已存在、DELETE 404=已删除等）
 * 由上层 `:core:data` 的备份仓库解释。传输层异常统一换成不带 query 的 [IOException]（同 NetworkErrors，防把凭证带进日志）。
 */
class WebDavClient(private val httpClient: HttpClient) {

    /** 一次 WebDAV 请求的结果：状态码 + 响应文本 + 去掉 query 的地址（只用于异常信息） */
    class Response(val code: Int, val body: String, val safeUrl: String) {
        val isSuccess: Boolean get() = code in 200..299
    }

    /** Depth:1 列目录。成功时服务器多半回 207 Multi-Status，用 [parseWebDavPropfind] 解 */
    suspend fun propFind(url: String, authorization: String): Response = execute(url) {
        method = PROP_FIND
        header(HttpHeaders.Depth, "1")
        header(HttpHeaders.Authorization, authorization)
        contentType(ContentType.Application.Xml)
        setBody(WEBDAV_PROPFIND_PROLOG)
    }

    /** 建目录。405/409 通常表示目录已存在，原状态码带回，上层判 */
    suspend fun mkcol(url: String, authorization: String): Response = execute(url) {
        method = MK_COL
        header(HttpHeaders.Authorization, authorization)
    }

    suspend fun putText(
        url: String,
        authorization: String,
        content: String,
        mediaType: ContentType = ContentType.Application.Json
    ): Response = execute(url) {
        method = HttpMethod.Put
        header(HttpHeaders.Authorization, authorization)
        contentType(mediaType)
        setBody(content)
    }

    suspend fun getText(url: String, authorization: String): Response = execute(url) {
        method = HttpMethod.Get
        header(HttpHeaders.Authorization, authorization)
    }

    suspend fun delete(url: String, authorization: String): Response = execute(url) {
        method = HttpMethod.Delete
        header(HttpHeaders.Authorization, authorization)
    }

    private suspend fun execute(target: String, configure: HttpRequestBuilder.() -> Unit): Response {
        val builder = HttpRequestBuilder().apply {
            url(target)
            configure()
        }
        val safeTarget = NetworkErrors.safeUrl(Url(builder.url.buildString()))
        return try {
            val response = httpClient.request(builder)
            Response(response.status.value, response.bodyAsText(), safeTarget)
        } catch (ce: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw IOException("WebDAV request cancelled for $safeTarget")
        } catch (e: Exception) {
            throw NetworkErrors.sanitize(e, safeTarget)
        }
    }

    companion object {
        val PROP_FIND: HttpMethod = HttpMethod("PROPFIND")
        val MK_COL: HttpMethod = HttpMethod("MKCOL")
    }
}

/** Basic 认证头（WebDAV 通用）：`Basic base64(username:password)`，UTF-8（同 c001apk 参考实现）。密码只在内存里存在本次会话 */
fun webDavBasicAuth(username: String, password: String): String =
    "Basic " + Base64.encode("$username:$password".encodeToByteArray())
