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
    /**
     * 登录密码（坚果云的「安全码」）。用户明确要求「密码要有记忆、保存登录信息」，
     * 所以随连接配置一起落盘；[rememberPassword] 为 false 时不存并在保存时清掉。
     */
    val password: String = "",
    /** 关掉「记住密码」后：本次输入仍可用，但不落盘，且会把已存的那份清除 */
    val rememberPassword: Boolean = true,
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
        /** 备份文件名固定一个：每次备份覆盖上一次（用户要求，网盘里不会堆一堆历史文件） */
        const val BACKUP_FILE_NAME = "trackr_backup.json"
    }
}

/** 读取 / 保存 [WebDavConnection]。保存永远不碰密码（不落盘） */
interface WebDavConfigRepository {
    suspend fun load(): WebDavConnection

    /** 空串按「没填」删除；[lastBackupAtMillis] 传 null 保留原值；[rememberPassword] 为 false 时密码不落盘并清掉存过的那份 */
    suspend fun saveConnection(
        baseUrl: String,
        username: String,
        remoteDir: String,
        password: String,
        rememberPassword: Boolean
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
            password = values.password.orEmpty(),
            rememberPassword = values.rememberPassword ?: true,
            lastBackupAtMillis = values.lastBackupAtMillis
        )
    }

    override suspend fun saveConnection(
        baseUrl: String,
        username: String,
        remoteDir: String,
        password: String,
        rememberPassword: Boolean
    ) {
        val current = store.loadWebDavConfig()
        store.saveWebDavConfig(
            WebDavConfigValues(
                baseUrl = baseUrl.trim().ifBlank { null },
                username = username.trim().ifBlank { null },
                // 旧的自定义前缀留着不动（只用于历史文件；新备份统一用固定文件名）
                filePrefix = current.filePrefix,
                remoteDir = remoteDir.trim().ifBlank { null },
                password = if (rememberPassword) password.trim().ifBlank { null } else null,
                rememberPassword = rememberPassword,
                lastBackupAtMillis = current.lastBackupAtMillis
            )
        )
    }

    override suspend fun recordBackupSuccess(atMillis: Long) {
        val current = store.loadWebDavConfig()
        store.saveWebDavConfig(current.copy(lastBackupAtMillis = atMillis))
    }
}
