package com.trackr.data.remote.amap

import com.trackr.core.data.repository.AmapKeyRepository
import com.trackr.core.data.repository.AmapRouteGateway
import com.trackr.core.model.AmapTipItem
import com.trackr.core.model.AmapTransitSchemeResult
import com.trackr.core.network.remote.amap.AmapTransitApiClient

/**
 * Android 的高德通道（不含高德 SDK 的精简构建）：只走 Web 服务（restapi.amap.com），
 * 不打包高德 SDK。Key 每次现读 [amapKeys]（「更多」页改了马上生效）。
 */
class AndroidAmapRouteGateway(
    private val amapApiClient: AmapTransitApiClient?,
    private val amapKeys: AmapKeyRepository?
) : AmapRouteGateway {

    /** 当前生效的 Web 服务 Key：用户配置 > 构建时注入（见 AmapKeyRepository.effectiveWebKey） */
    private fun effectiveWebServiceKey(): String =
        amapKeys?.effectiveWebKey?.value.orEmpty()

    override fun isConfigured(): Boolean =
        amapKeys?.privacyConsent?.value == true && effectiveWebServiceKey().isNotEmpty()

    override suspend fun searchTips(
        keyword: String,
        cityName: String,
        location: String?
    ): Result<List<AmapTipItem>> {
        if (amapKeys?.privacyConsent?.value != true) {
            return Result.failure(IllegalStateException("未同意高德隐私说明"))
        }
        val webKey = effectiveWebServiceKey()
        val web = amapApiClient
        if (webKey.isNotEmpty() && web != null) {
            return web.searchPoiTips(keyword, cityName, webKey, location)
        }
        return Result.failure(IllegalStateException("未配置高德 Web Key"))
    }

    override suspend fun planTransit(
        originLngLat: String,
        destinationLngLat: String,
        originName: String,
        destName: String,
        cityName: String,
        cityd: String?
    ): Result<List<AmapTransitSchemeResult>> {
        if (amapKeys?.privacyConsent?.value != true) {
            return Result.failure(IllegalStateException("未同意高德隐私说明"))
        }
        val webKey = effectiveWebServiceKey()
        val web = amapApiClient
        if (webKey.isNotEmpty() && web != null) {
            return web.planTransit(
                originLngLat = originLngLat,
                destinationLngLat = destinationLngLat,
                originName = originName,
                destName = destName,
                city = cityName,
                apiKey = webKey,
                cityd = cityd
            )
        }
        return Result.failure(IllegalStateException("未配置高德 Web Key"))
    }
}
