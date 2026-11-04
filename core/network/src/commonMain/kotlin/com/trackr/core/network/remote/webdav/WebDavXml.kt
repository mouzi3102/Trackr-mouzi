package com.trackr.core.network.remote.webdav

/*
 * PROPFIND (Depth:1) 的 multistatus 响应是很小的 XML，commonMain 没有 XML 解析器，这里手写一个宽容的扫描器。
 * 只认规范里的结构（RFC 4918 §9.1）：`<response>` -> `<href>`；`<propstat>` -> `<prop>` ->
 * `<displayname>` / `<getlastmodified>` / `<resourcetype>`（里面 `<collection/>` 表示目录）。
 * 带命名空间前缀（`D:`、`d:` 等）也能读：匹配时只看去掉前缀后的本地名，大小写不敏感。
 * 读不出来的块整个跳过，不让一个坏条目拖垮整个列表。
 */

/** multistatus 里一个 `<response>`（目录或文件） */
data class WebDavPropfindEntry(
    val href: String,
    /** href 最后一段的解码结果（做 GET/PUT/DELETE 目标用这个，不用 displayname——那是展示名） */
    val name: String,
    val isCollection: Boolean,
    val displayname: String? = null,
    val lastModified: String? = null
)

/** Depth:1 的 PROPFIND 请求体（DAV: 命名空间、三个属性就够列目录了） */
const val WEBDAV_PROPFIND_PROLOG: String =
    "<?xml version=\"1.0\" encoding=\"utf-8\" ?>" +
        "<d:propfind xmlns:d=\"DAV:\"><d:prop>" +
        "<d:displayname /><d:getlastmodified /><d:resourcetype />" +
        "</d:prop></d:propfind>"

/**
 * 解析 PROPFIND 响应。不是 multistatus、完全没有 `response` 块或结构坏了都只返回能读出的部分（可能是空列表）。
 */
fun parseWebDavPropfind(xml: String): List<WebDavPropfindEntry> {
    val entries = ArrayList<WebDavPropfindEntry>()
    var searchFrom = 0
    while (true) {
        val start = findTag(xml, "response", searchFrom, opening = true) ?: break
        val contentStart = tagContentStart(xml, start)
        if (contentStart < 0) {
            // 自闭合或残缺的 response：跳过这一块继续找下一块
            searchFrom = start + 1
            continue
        }
        val end = findTag(xml, "response", contentStart + 1, opening = false) ?: break
        val block = xml.substring(contentStart, end)
        val href = readTagValue(block, "href")
        if (href != null) {
            val resourcetype = readTagValue(block, "resourcetype")
            entries += WebDavPropfindEntry(
                href = href,
                name = webDavNameFromUrl(href),
                isCollection = resourcetype != null &&
                    findTag(resourcetype, "collection", 0, opening = true) != null,
                displayname = readTagValue(block, "displayname"),
                lastModified = readTagValue(block, "getlastmodified")
            )
        }
        searchFrom = end + 1
    }
    return entries
}

/**
 * 找局部名为 [local]（大小写不敏感，允许 `D:` 之类前缀）的开标签（opening=true）或闭标签（opening=false）。
 * 返回 `<` 的位置。
 */
private fun findTag(text: String, local: String, from: Int, opening: Boolean): Int? {
    var i = from
    while (true) {
        i = text.indexOf('<', i)
        if (i < 0) return null
        // opening=true 要的是开标签（isClose=false）；两者相等说明找到的不是要的那种
        val isClose = text.startsWith("</", i)
        if (isClose == opening) {
            i++
            continue
        }
        var p = i + if (isClose) 2 else 1
        val nameStart = p
        while (p < text.length && isNameChar(text[p])) p++
        if (p < text.length && text[p] == ':') {
            p++
            while (p < text.length && isNameChar(text[p])) p++
        }
        val candidate = text.substring(nameStart, p)
        val matchesName = candidate.equals(local, ignoreCase = true) ||
            candidate.substringAfterLast(':').equals(local, ignoreCase = true)
        if (matchesName && p > nameStart && afterNameIsTagEnd(text, p)) return i
        i = if (p > i) p else i + 1
    }
}

private fun isNameChar(ch: Char): Boolean =
    ch.isLetterOrDigit() || ch == '_' || ch == '.' || ch == '-'

private fun afterNameIsTagEnd(text: String, afterName: Int): Boolean =
    afterName < text.length && (text[afterName] == '>' || text[afterName] == '/' || text[afterName].isWhitespace())

/** 开标签的内容起点（ '>' 之后）；自闭合（`<x/>`）返回 [SELF_CLOSING] */
private fun tagContentStart(text: String, openTagStart: Int): Int {
    val gt = text.indexOf('>', openTagStart)
    if (gt < 0) return -1
    return if (gt > openTagStart + 1 && text[gt - 1] == '/') SELF_CLOSING else gt + 1
}

private const val SELF_CLOSING = -2

/** 读出 `<local …>value</local>` 的 value（XML 实体已反转义）；自闭合、缺失、没有闭标签都给 null */
private fun readTagValue(text: String, local: String): String? {
    val open = findTag(text, local, 0, opening = true) ?: return null
    val valueStart = tagContentStart(text, open)
    if (valueStart == SELF_CLOSING) return ""
    if (valueStart < 0) return null
    val close = findTag(text, local, valueStart, opening = false) ?: return null
    return unescapeXml(text.substring(valueStart, close).trim())
}

/** `&amp;`、数字实体等反转义 */
private fun unescapeXml(raw: String): String {
    if (!raw.contains('&')) return raw
    val out = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        val ch = raw[i]
        if (ch != '&') {
            out.append(ch)
            i++
            continue
        }
        val semi = raw.indexOf(';', i + 1)
        if (semi < 0 || semi - i > 10) {
            out.append(ch)
            i++
            continue
        }
        val entity = raw.substring(i + 1, semi)
        val decoded = when {
            entity == "amp" -> "&"
            entity == "lt" -> "<"
            entity == "gt" -> ">"
            entity == "quot" -> "\""
            entity == "apos" -> "'"
            entity.startsWith("#x") || entity.startsWith("#X") ->
                entity.substring(2).toIntOrNull(16)?.takeIf { it <= 0xFFFF }?.let { it.toChar().toString() }
            entity.startsWith("#") ->
                entity.substring(1).toIntOrNull()?.takeIf { it in 0..0xFFFF }?.let { it.toChar().toString() }
            else -> null
        }
        if (decoded == null) {
            out.append(ch)
            i++
        } else {
            out.append(decoded)
            i = semi + 1
        }
    }
    return out.toString()
}
