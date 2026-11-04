package com.trackr.core.network.remote.webdav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PROPFIND (Depth:1) 响应解析的表格测试（结构参照 RFC 4918 §9.1 与真实服务器：坚果云带 `D:` 前缀、
 * Apache 用默认命名空间、href 里目录尾斜杠、displayname 可能带 XML 实体）。
 */
class WebDavXmlTest {

    @Test
    fun parseDnsPrefixedPropfind() {
        val xml = """
            <?xml version="1.0"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/dav/backups/</D:href>
                <D:propstat>
                  <D:prop>
                    <D:resourcetype><D:collection/></D:resourcetype>
                    <D:getlastmodified>Mon, 02 Nov 2026 08:00:00 GMT</D:getlastmodified>
                  </D:prop>
                  <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
              </D:response>
              <D:response>
                <D:href>/dav/backups/trackr_backup_20261102-080000.json</D:href>
                <D:propstat>
                  <D:prop>
                    <D:displayname>trackr_backup_20261102-080000.json</D:displayname>
                    <D:getlastmodified>Mon, 02 Nov 2026 08:00:01 GMT</D:getlastmodified>
                    <D:resourcetype/>
                  </D:prop>
                  <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()
        val entries = parseWebDavPropfind(xml)
        assertEquals(2, entries.size)
        assertEquals(WebDavPropfindEntry("/dav/backups/", "backups", true, null, "Mon, 02 Nov 2026 08:00:00 GMT"), entries[0])
        assertEquals(
            WebDavPropfindEntry(
                href = "/dav/backups/trackr_backup_20261102-080000.json",
                name = "trackr_backup_20261102-080000.json",
                isCollection = false,
                displayname = "trackr_backup_20261102-080000.json",
                lastModified = "Mon, 02 Nov 2026 08:00:01 GMT"
            ),
            entries[1]
        )
    }

    @Test
    fun parseNoNamespaceUppercaseAndEntities() {
        val xml = """
            <multistatus xmlns="DAV:">
              <response>
                <HREF>http://host/dav/%E5%A4%87%E4%BB%BDA%26B.json</HREF>
                <propstat><prop>
                  <displayname>备份A&amp;B</displayname>
                  <getlastmodified>Tue, 03 Nov 2026 00:00:00 GMT</getlastmodified>
                </prop></propstat>
              </response>
            </multistatus>
        """.trimIndent()
        val entries = parseWebDavPropfind(xml)
        assertEquals(1, entries.size)
        val e = entries[0]
        assertEquals("备份A&B.json", e.name)
        assertEquals("备份A&B", e.displayname)
        assertTrue(!e.isCollection)
        assertEquals("Tue, 03 Nov 2026 00:00:00 GMT", e.lastModified)
    }

    @Test
    fun tolerantToBrokenOrMissingParts() {
        val xml = """
            <?xml version="1.0"?><d:multistatus xmlns:d="DAV:">
              <d:response><!-- 没有 href，跳过 --><d:propstat><d:prop><d:displayname>x</d:displayname></d:prop></d:propstat></d:response>
              <d:response><d:href>/a/one.json</d:href><!-- 没有 propstat：仍能列出来 --></d:response>
              <d:response><d:href>/a/own.json</d:propstat></d:response>
              <d:response><d:href>/a/two.json</d:href><d:propstat><d:prop><d:resourcetype>not-xml<</d:resourcetype></d:prop></d:propstat></d:response>
            </d:multistatus>
        """.trimIndent()
        val entries = parseWebDavPropfind(xml)
        assertEquals(listOf("/a/one.json", "/a/two.json"), entries.map { it.href })
        assertEquals(false, entries[1].isCollection)
    }

    @Test
    fun emptyGarbageReturnsEmptyList() {
        val cases = listOf("", "not xml at all", "<multistatus/>", "<response><href>/a</href>")
        cases.forEach { assertTrue("case: $it") { parseWebDavPropfind(it).isEmpty() } }
    }

    @Test
    fun multiplePropsInSplitPropstats() {
        val xml = """
            <D:multistatus xmlns:D="DAV:"><D:response>
              <D:href>/d/f.json</D:href>
              <D:propstat><D:prop><D:displayname>f.json</D:displayname></D:prop><D:status>200</D:status></D:propstat>
              <D:propstat><D:prop><D:getcontentlength>512</D:getcontentlength></D:prop><D:status>404</D:status></D:propstat>
            </D:response></D:multistatus>
        """.trimIndent()
        val entries = parseWebDavPropfind(xml)
        assertEquals("f.json", entries.single().displayname)
    }
}
