package com.trackr.core.data.backup

import com.trackr.core.data.local.TransitLocalStore
import com.trackr.core.data.local.WebDavConfigValues

/**
 * WebDAV 备份的连接配置（持久化部分）。
 *
 * **这里没有密码字段，也不会有**：密码只在「WebDAV 备份」页的 ViewModel 内存里活本次进程会话，
 * 落盘的设置 DataStore 里永远只有下面这几项非敏感标量（同 c001apk 参考设计里除「密码明文存本机」外的取舍，我们更严一档）。
 */
data class WebDavConnection(
    /** 服务器地址（含目录，可空 = 还没配） */
    val baseUrl: String = "",
    val username: String = "",
    /** 远端目录路径；空 = 直接建在服务器地址指向的目录下 */
    val remoteDir: String = DEFAULT_REMOTE_DIR,
    /** 备份文件名前缀；留空用 [DEFAULT_FILE_PREFIX] */
    val filePrefix: String = DEFAULT_FILE_PREFIX,
    /** 上次成功备份时刻（epoch millis）；null = 从没成功过 */
    val lastBackupAtMillis: Long? = null
) {
    companion object {
        const val DEFAULT_REMOTE_DIR = "trackr_backups"

        /**
         * 「用坚果云」一键填入的服务器地址。坚果云 WebDAV 只支持 https，且路径必须以 `/dav` 开头
         * （`normalizeWebDavBaseUrl` 会替用户把没写全的补上）；用户名填账户邮箱、密码填「安全码」。
         */
        const val PRESET_JIANGUOYUN_URL = "https://dav.jianguoyun.com/dav/"
        const val DEFAULT_FILE_PREFIX = "trackr_backup"
    }
}

/** 读取 / 保存 [WebDavConnection]。保存永远不碰密码（不落盘） */
interface WebDavConfigRepository {
    suspend fun load(): WebDavConnection

    /** 四舍五入的语义：空串按「没填」删除；[lastBackupAtMillis] 传 null 保留原值 */
    suspend fun saveConnection(
        baseUrl: String,
        username: String,
        remoteDir: String,
        filePrefix: String
    )

    /** PUT 成功后记「上次备份时间」 */
    suspend fun recordBackupSuccess(atMillis: Long)
}

/** 存在设置存储里（新 = `trackr_settings`，旧 = `trackr_prefs`，同一个 [TransitLocalStore] 抽象），与其它设置同库 */
class StoreWebDavConfigRepository(private val store: TransitLocalStore) : WebDavConfigRepository {

    override suspend fun load(): WebDavConnection {
        val values = store.loadWebDavConfig()
        return WebDavConnection(
            baseUrl = values.baseUrl.orEmpty(),
            username = values.username.orEmpty(),
            remoteDir = values.remoteDir?.takeIf { it.isNotEmpty() } ?: WebDavConnection.DEFAULT_REMOTE_DIR,
            filePrefix = values.filePrefix?.takeIf { it.isNotBlank() } ?: WebDavConnection.DEFAULT_FILE_PREFIX,
            lastBackupAtMillis = values.lastBackupAtMillis
        )
    }

    override suspend fun saveConnection(baseUrl: String, username: String, remoteDir: String, filePrefix: String) {
        val current = store.loadWebDavConfig()
        store.saveWebDavConfig(
            WebDavConfigValues(
                baseUrl = baseUrl.trim().ifBlank { null },
                username = username.trim().ifBlank { null },
                remoteDir = remoteDir.trim().ifBlank { null },
                filePrefix = filePrefix.trim().ifBlank { null },
                lastBackupAtMillis = current.lastBackupAtMillis
            )
        )
    }

    override suspend fun recordBackupSuccess(atMillis: Long) {
        val current = store.loadWebDavConfig()
        store.saveWebDavConfig(current.copy(lastBackupAtMillis = atMillis))
    }
}
