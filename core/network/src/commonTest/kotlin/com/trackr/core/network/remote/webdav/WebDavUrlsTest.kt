package com.trackr.core.network.remote.webdav

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * WebDAV URL 拼装 / 转义 / 反解析的表格测试：目录、文件名可能有空格、中文、`%`，
 * 且用户可能少打或多打斜杠、少打 scheme（坚果云 / 群晖习惯不同）。
 */
class WebDavUrlsTest {

    @Test
    fun normalizeBaseUrl() {
        val cases = listOf(
            "https://dav.example.com/dav" to "https://dav.example.com/dav/",
            " https://dav.example.com/dav/ " to "https://dav.example.com/dav/",
            "dav.example.com/dav" to "https://dav.example.com/dav/",
            "https://dav.example.com:8443" to "https://dav.example.com:8443/"
        )
        cases.forEach { (input, expected) -> assertEquals(expected, normalizeWebDavBaseUrl(input), input) }
    }

    @Test
    fun jianGuoYunUrlIsFixedUp() {
        // 坚果云两条硬性要求：必须 https、路径以 /dav 开头（应用侧也禁明文，所以缺 scheme 一律按 https 补）
        val cases = listOf(
            "dav.jianguoyun.com" to "https://dav.jianguoyun.com/dav/",
            "http://dav.jianguoyun.com/dav" to "https://dav.jianguoyun.com/dav/",
            "https://dav.jianguoyun.com" to "https://dav.jianguoyun.com/dav/",
            "https://dav.jianguoyun.com/mypath" to "https://dav.jianguoyun.com/dav/mypath/",
            "https://dav.jianguoyun.com/dav/" to "https://dav.jianguoyun.com/dav/",
            "https://dav.jianguoyun.com/dav/备份" to "https://dav.jianguoyun.com/dav/备份/"
        )
        cases.forEach { (input, expected) -> assertEquals(expected, normalizeWebDavBaseUrl(input), input) }
        assertTrue(isJianguoYunUrl("https://Dav.JiaNguoYun.com/dav"))
        assertFalse(isJianguoYunUrl("https://nas.local/webdav"))
    }

    @Test
    fun joinUrlEscapesEachSegment() {
        val cases = listOf(
            // 普通拼接，恰好一个斜杠
            arrayOf("https://h/dav", "backups", "trackr_backup_2026.json") to "https://h/dav/backups/trackr_backup_2026.json",
            // 空格与中文（UTF-8 百分号编码）
            arrayOf("https://h/dav", "我的 备份", "f 1.json") to
                "https://h/dav/%E6%88%91%E7%9A%84%20%E5%A4%87%E4%BB%BD/f%201.json",
            // 内部斜杠当分隔符
            arrayOf("https://h/dav", "a/b", "c.json") to "https://h/dav/a/b/c.json",
            // 已经转义过的 %XX 不重复转义
            arrayOf("https://h/s%20p%20a", "x.json") to "https://h/s%20p%20a/x.json",
            // 裸 % 要转义、目录尾斜杠保留
            arrayOf("https://h/dav/", "100% sure/") to "https://h/dav/100%25%20sure/",
            // 空段跳过
            arrayOf("https://h/dav", "", "/x.json") to "https://h/dav/x.json"
        )
        cases.forEach { (parts, expected) ->
            assertEquals(expected, joinWebDavUrl(parts.first(), *parts.drop(1).toTypedArray()), parts.joinToString("|"))
        }
    }

    @Test
    fun encodeIsExactForNonAscii() {
        assertEquals("%E4%B8%AD%E6%96%87", joinWebDavUrl("https://h/", "中文").removePrefix("https://h/"))
        assertEquals("a%E2%9D%A4b", joinWebDavUrl("https://h", "a❤b").removePrefix("https://h/"))
    }

    @Test
    fun percentDecodeRoundTrip() {
        val cases = listOf(
            "plain.json" to "plain.json",
            "a%20b" to "a b",
            "100%25" to "100%",
            "%E4%B8%AD%E6%96%87" to "中文",
            // 非法序列原样保留（% 本身不是百分号转义就照抄）
            "%zz1" to "%zz1"
        )
        cases.forEach { (input, expected) -> assertEquals(expected, percentDecode(input), input) }
    }

    @Test
    fun nameFromUrl() {
        val cases = listOf(
            "https://h/dav/backups/trackr_backup_1.json" to "trackr_backup_1.json",
            "https://h/dav/f%201.json" to "f 1.json",
            "https://h/dav/dir/" to "dir",
            "https://h/dav/x?raw=1" to "x"
        )
        cases.forEach { (input, expected) -> assertEquals(expected, webDavNameFromUrl(input), input) }
    }
}
