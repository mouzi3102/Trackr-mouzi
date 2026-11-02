package com.trackr.data.remote.amap

import com.trackr.core.domain.platform.AmapSdkKeyApplier
import com.trackr.core.domain.platform.MapSdkInitializer

/**
 * 不含高德 SDK 的精简构建下的空实现：Key 只用于 Web 服务（restapi.amap.com），
 * 无需注入任何 SDK。接口保留是为了 :androidApp 的 AmapSdkKeySync / TrackrApp 调用点零改动。
 */
class AndroidAmapSdkKeyApplier : AmapSdkKeyApplier {
    override fun apply(customKey: String?) = Unit
}

/** [MapSdkInitializer] 的空实现：无 SDK 可初始化，仅保留接口兼容。 */
class AndroidMapSdkInitializer : MapSdkInitializer {
    override fun configureBuiltInKeys(builtInAndroidKey: String, builtInWebKey: String) = Unit
    override fun initialize(userAndroidKey: String?) = Unit
}
