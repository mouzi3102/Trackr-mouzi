package com.trackr.core.network.remote.webdav

import com.trackr.core.network.testing.MockReply
import com.trackr.core.network.testing.mockHttpClient
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Basic 认证头：`Basic base64(utf8(user:pass))`——和参考实现「'Basic ${base64Encode(utf8.encode('$username:$password'))}'」同一规则 */
class WebDavAuthTest {

    @Test
    fun basicHeaderVectors() {
        val cases = listOf(
            Triple("user", "pass", "Basic dXNlcjpwYXNz"),
            Triple("", "", "Basic Og=="),
            Triple("a", "b", "Basic YTpi"),
            Triple("a@b.com", "p:ss:w", "Basic YUBiLmNvbTpwOnNzOnc="),
            // 中文按 UTF-8（坚果云用户名常是邮箱，密码可能有符号）
            Triple("用户", "密码", "Basic 55So5oi3OuWvhueggQ==")
        )
        cases.forEach { (user, pass, expected) ->
            assertEquals(expected, webDavBasicAuth(user, pass), "$user:$pass")
        }
    }
}

/** WebDAV 动词层：方法名、Depth 头、prolog、URL 转义、认证头全部钉死（MockEngine，不发真实请求） */
class WebDavClientVerbsTest {

    @Test
    fun propfindCarriesDepthAuthAndProlog() = runTest {
        var seenMethod = ""
        var seenDepth = ""
        var seenAuth = ""
        var seenType = ""
        var seenBody = ""
        var seenUrl = ""
        val client = WebDavClient(mockHttpClient { req ->
            seenMethod = req.data.method.value
            seenDepth = req.header(HttpHeaders.Depth).orEmpty()
            seenAuth = req.header(HttpHeaders.Authorization).orEmpty()
            seenType = req.contentType.orEmpty()
            seenBody = req.body
            seenUrl = req.url
            MockReply("<D:multistatus xmlns:D=\"DAV:\"/>", 207)
        })
        val resp = client.propFind(joinWebDavUrl("https://h/dav", "back ups"), webDavBasicAuth("u", "p"))
        assertEquals(207, resp.code)
        assertEquals("PROPFIND", seenMethod)
        assertEquals("1", seenDepth)
        assertEquals("Basic dTpw", seenAuth)
        assertTrue(seenType.startsWith("application/xml"), seenType)
        assertTrue(seenBody.contains("<d:propfind"), seenBody)
        assertTrue(seenBody.contains("<d:displayname"), seenBody)
        assertEquals("https://h/dav/back%20ups", resp.safeUrl)
    }

    @Test
    fun putGetDeleteUseTargetUrls() = runTest {
        val recorded = mutableListOf<Triple<String, String, String>>()
        val http = mockHttpClient { req ->
            recorded += Triple(req.data.method.value, req.url, req.body)
            when (req.data.method.value) {
                "PUT" -> MockReply("created", 201)
                "DELETE" -> MockReply("", 204)
                else -> MockReply("file-body", 200)
            }
        }
        val client = WebDavClient(http)
        val auth = webDavBasicAuth("u", "p")
        val url = joinWebDavUrl("https://h/dav/bk", "f 1.json")
        assertEquals(201, client.putText(url, auth, "{\"a\":1}").code)
        assertEquals("file-body", client.getText(url, auth).body)
        assertEquals(204, client.delete(url, auth).code)
        assertEquals(listOf("PUT", "GET", "DELETE"), recorded.map { it.first })
        assertTrue(recorded.all { it.second == "https://h/dav/bk/f%201.json" }, recorded.joinToString())
        assertEquals("{\"a\":1}", recorded[0].third)
    }

    @Test
    fun mkcolCodesPassThrough() = runTest {
        var code = 201
        val client = WebDavClient(mockHttpClient { MockReply("", code) })
        assertEquals(201, client.mkcol("https://h/dav/n/", webDavBasicAuth("u", "p")).code)
        code = 405
        assertEquals(405, client.mkcol("https://h/dav/n/", webDavBasicAuth("u", "p")).code)
        code = 409
        assertEquals(409, client.mkcol("https://h/dav/n/", webDavBasicAuth("u", "p")).code)
    }

    @Test
    fun transportErrorIsSanitizedIoException() = runTest {
        val client = WebDavClient(mockHttpClient {
            throw IOException("socket fail https://h/dav/x.json?token=secret")
        })
        val e = assertFailsWith<IOException> { client.getText("https://h/dav/x.json", webDavBasicAuth("u", "p")) }
        assertTrue(e.message!!.contains("https://h/dav/x.json"), e.message ?: "")
        assertTrue(!e.message!!.contains("token=secret"), e.message ?: "")
    }
}
