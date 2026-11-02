package com.trackr.ui.screens.linedetail

import com.trackr.core.data.local.NoLineStopsCache
import com.trackr.core.data.provider.TransitProviderCoordinator
import com.trackr.core.data.repository.DefaultRealtimeRepository
import com.trackr.core.data.repository.DefaultTrackingRepository
import com.trackr.core.data.repository.InMemoryTrackingSessionStore
import com.trackr.core.domain.platform.NoAnnouncer
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.TransitCity
import com.trackr.core.network.remote.chelaile.ChelaileApiClient
import com.trackr.core.network.remote.common.createTrackrHttpClient
import com.trackr.core.network.remote.mygolbs.MygolbsApiClient
import com.trackr.core.network.provider.chelaile.ChelaileTransitProvider
import com.trackr.core.network.provider.mygolbs.MygolbsTransitProvider
import com.trackr.core.testing.FakeFavoritesRepository
import com.trackr.core.testing.FakeSearchHistoryRepository
import com.trackr.core.testing.FakeSettingsRepository
import com.trackr.core.testing.FakeTrackingController
import com.trackr.core.testing.MainDispatcherHolder
import com.trackr.data.repository.FakeScheduledReminderRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test

/**
 * 真机全链路复现：真实 Chelaile/Mygolbs 网络 + TransitProviderCoordinator + DefaultRealtimeRepository(SingleFlightCache)
 * 驱动 LineDetailViewModel，模拟「打开 APP 立刻进入线路」（无定位坐标），观察 onSuccess 内层 catch 是否打印 LinkedHashMap NPE 堆栈。
 */
@RunWith(RobolectricTestRunner::class)
class RealLineDetailReproTest {
    private val main = MainDispatcherHolder(UnconfinedTestDispatcher())

    @Test
    fun realSuzhou26ThroughViewModel() = runBlocking {
        main.install()
        try {
            val http = createTrackrHttpClient(requestTimeoutMillis = 30_000)

            val chelaileClient = ChelaileApiClient(http)
            val mygolbsClient = MygolbsApiClient(http)
            val coordinator = TransitProviderCoordinator(
                chelaileProvider = ChelaileTransitProvider(chelaileClient),
                mygolbsProvider = MygolbsTransitProvider(mygolbsClient)
            )
            val settings = FakeSettingsRepository(TransitCity(cityId = "374", cityName = "宿州"))
            val favorites = FakeFavoritesRepository()
            val searchHistory = FakeSearchHistoryRepository()
            val realtime = DefaultRealtimeRepository(
                coordinator = coordinator,
                settings = settings,
                favoritesRepository = favorites,
                searchHistory = searchHistory,
                userLocation = { null }
            )

            val trackingRepository = DefaultTrackingRepository(
                InMemoryTrackingSessionStore(),
                CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            )
            val vm = LineDetailViewModel(
                cityId = "374",
                initialLineId = "557189281976",
                initialTargetOrder = 0,
                initialStationId = "374-47",
                initialSource = DataSourceType.CHELAILE,
                settings = settings,
                favorites = favorites,
                realtime = realtime,
                reminderRepository = FakeScheduledReminderRepository(),
                trackingRepository = trackingRepository,
                trackingController = FakeTrackingController(trackingRepository),
                lineStopsCache = NoLineStopsCache,
                announcer = NoAnnouncer,
                enableAutoPolling = false
            )
            vm.loadLineDetail()
            delay(15_000)
            println("VM_STATE errorMessage=${vm.uiState.value.errorMessage} stops=${vm.uiState.value.static?.stops?.size} buses=${vm.uiState.value.live?.buses?.size}")
        } finally {
            main.reset()
        }
    }
}
