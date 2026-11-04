package com.trackr.core.data.backup

import com.trackr.core.network.remote.webdav.WebDavClient
import io.ktor.client.HttpClient

/** 一次 WebDAV 动词调用的原始结果（状态码 + 文本响应），给备份仓库解释语义 */
data class WebDavRaw(val code: Int, val body: String, val safeUrl: String) {
    val isSuccess: Boolean get() = code in 200..299
}

/** 可测替身口：`DefaultBackupRepository` 只通过这个接口摸到网络（单测塞假实现，不发真实请求） */
interface WebDavTransport {
    suspend fun propFind(url: String, authorization: String): WebDavRaw
    suspend fun mkcol(url: String, authorization: String): WebDavRaw
    suspend fun putText(url: String, authorization: String, content: String): WebDavRaw
    suspend fun getText(url: String, authorization: String): WebDavRaw
    suspend fun delete(url: String, authorization: String): WebDavRaw
}

/** 用 `:core:network` 的 WebDAV 动词客户端实现（Basic 认证头由调用方传进来，这里不持有任何凭证） */
class KtorWebDavTransport(httpClient: HttpClient) : WebDavTransport {
    private val client = WebDavClient(httpClient)

    override suspend fun propFind(url: String, authorization: String): WebDavRaw =
        client.propFind(url, authorization).toRaw()

    override suspend fun mkcol(url: String, authorization: String): WebDavRaw =
        client.mkcol(url, authorization).toRaw()

    override suspend fun putText(url: String, authorization: String, content: String): WebDavRaw =
        client.putText(url, authorization, content).toRaw()

    override suspend fun getText(url: String, authorization: String): WebDavRaw =
        client.getText(url, authorization).toRaw()

    override suspend fun delete(url: String, authorization: String): WebDavRaw =
        client.delete(url, authorization).toRaw()

    private fun WebDavClient.Response.toRaw() = WebDavRaw(code, body, safeUrl)
}
