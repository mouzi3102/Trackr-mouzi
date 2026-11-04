package com.trackr.core.network.remote.webdav

/*
 * WebDAV 的 URL 拼装与路径转义（「我的」WebDAV 备份用，模仿 c001apk 的目录即路径设计）。
 *
 * 通用做法：
 * - 服务器地址允许带目录路径（坚果云 `/dav`、群晖 `/webdav`），尾部补 /；没写 scheme 时按 **https** 补
 *   （Android 的 network_security_config 禁明文，补 http:// 等于必失败；局域网 http 请自己写全 scheme）。
 * - 目录和文件名里可能有空格、中文，按 RFC 3986 逐段百分号转义；已经是 `%XX` 的不重复转义。
 * - 坚果云有两条硬性要求，这里替用户补掉：**必须 https**、路径必须以 `/dav` 开头
 *   （只填到域名时自动补 `/dav`；填了 `https://dav.jianguoyun.com/mypath` 这类会纠成 `/dav/mypath`）。
 */

/** 「用坚果云」一键填入的服务器地址（用户名 = 账户邮箱，密码 = 坚果云「安全码」而不是登录密码） */
const val JIANGUOYUN_PRESET_URL = "https://dav.jianguoyun.com/dav/"

/** 地址里是不是坚果云（`dav.jianguoyun.com` / `www.jianguoyun.com` 都算） */
fun isJianguoYunUrl(raw: String): Boolean = raw.lowercase().contains("jianguoyun.com")

/** 每个路径段都保留的安全字符（RFC 3986 unreserved + sub-delims + @ = 常见 WebDAV 路径里的符号） */
private const val UNRESERVED_PATH_SEGMENT_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~!$&'()*+,;=:@/"

private val HEX_DIGITS = "0123456789abcdefABCDEF"

/** 补 scheme（缺省 https）、坚果云纠偏、保证以 / 结尾 */
fun normalizeWebDavBaseUrl(raw: String): String {
    var url = raw.trim()
    if (url.isEmpty()) return url
    if (!url.contains("://")) {
        url = if (url.startsWith("//")) ":$url" else "https://$url"
    }
    if (isJianguoYunUrl(url)) url = withJianguoYunPath(url)
    if (!url.endsWith("/")) url += "/"
    return url
}

/** 坚果云：强制 https（明文被应用的网络策略禁掉）+ 路径必须以 `/dav` 开头 */
private fun withJianguoYunPath(url: String): String {
    val secure = if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url
    val schemeEnd = secure.indexOf("://") + 3
    val schemeHead = secure.substring(0, schemeEnd)
    val hostAndPath = secure.substring(schemeEnd)
    val slash = hostAndPath.indexOf('/')
    val host = if (slash >= 0) hostAndPath.substring(0, slash) else hostAndPath
    val path = if (slash >= 0) hostAndPath.substring(slash) else ""
    return when {
        path.isEmpty() -> "$schemeHead$host/dav"
        path == "/dav" || path.startsWith("/dav/") -> secure
        else -> "$schemeHead$host/dav/${path.removePrefix("/")}"
    }
}

/** 百分号解码（按 UTF-8 还原字节）；遇到非法的 `%` 序列原样保留该字符（容错，个别 href 坏了不影响其余文件） */
fun percentDecode(input: String): String {
    val bytes = ByteArray(input.length)
    var size = 0
    var i = 0
    while (i < input.length) {
        val ch = input[i]
        if (ch == '%' && i + 2 < input.length &&
            input[i + 1] in HEX_DIGITS && input[i + 2] in HEX_DIGITS
        ) {
            bytes[size++] = input.substring(i + 1, i + 3).toInt(16).toByte()
            i += 3
        } else {
            bytes[size++] = ch.code.toByte()
            i++
        }
    }
    return bytes.copyOf(size).decodeToString()
}

/** 单段转义：安全字符保留，`%XX` 视为已转义保留，其余字节按 UTF-8 写成 %XX */
private fun encodeWebDavSegment(segment: String): String = buildString(segment.length) {
    var i = 0
    while (i < segment.length) {
        val ch = segment[i]
        when {
            ch == '%' && i + 2 < segment.length &&
                segment[i + 1] in HEX_DIGITS && segment[i + 2] in HEX_DIGITS -> {
                append(ch)
                append(segment[i + 1])
                append(segment[i + 2])
                i += 3
            }
            ch in UNRESERVED_PATH_SEGMENT_CHARS -> {
                append(ch)
                i++
            }
            else -> {
                ch.toString().encodeToByteArray().forEach { b ->
                    append('%')
                    val v = b.toInt() and 0xFF
                    append(HEX_DIGITS.uppercase()[v / 16])
                    append(HEX_DIGITS.uppercase()[v % 16])
                }
                i++
            }
        }
    }
}

/**
 * 拼一个 WebDAV 资源地址：`base` 规范化后逐段转义拼上 `parts`（parts 里的 / 当分隔符保留，
 * 其余字符转义）。传空串跳过，目录间保证恰好一个斜杠。
 */
fun joinWebDavUrl(base: String, vararg parts: String): String {
    val builder = StringBuilder(normalizeWebDavBaseUrl(base))
    parts.filter { it.isNotBlank() }.forEach { part ->
        val encoded = part.split('/').joinToString("/") { encodeWebDavSegment(it) }.trim('/')
        if (encoded.isNotEmpty()) {
            // 段与段之间补恰好一个斜杠（base 规范化后已带斜杠时不重复补）
            if (builder.isNotEmpty() && builder.last() != '/') builder.append('/')
            builder.append(encoded)
            if (part.endsWith('/')) builder.append('/')
        }
    }
    return builder.toString()
}

/** 从资源 URL 取最后一个路径段并解码（远端文件名） */
fun webDavNameFromUrl(url: String): String {
    val noQuery = url.substringBefore('#').substringBefore('?')
    val segment = noQuery.trimEnd('/').substringAfterLast('/')
    return percentDecode(segment)
}
