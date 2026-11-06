package com.trackr.feature.bus.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackr.core.data.repository.CitySearchHistoryRepository
import com.trackr.core.data.repository.FavoritesRepository
import com.trackr.core.data.repository.HomeCoordinates
import com.trackr.core.data.repository.LocationRepository
import com.trackr.core.data.repository.RealtimeRepository
import com.trackr.core.data.repository.SettingsRepository
import com.trackr.core.data.repository.TrackingRepository
import com.trackr.core.data.repository.UserLocationStore
import com.trackr.core.domain.platform.Announcer
import com.trackr.core.domain.platform.NoAnnouncer
import com.trackr.core.domain.platform.ProcessMonotonicClock
import com.trackr.core.domain.platform.TrackingController
import com.trackr.core.domain.platform.TransitCodeLauncher
import com.trackr.core.model.EngineMode
import com.trackr.core.model.ProvinceCityCatalog
import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitCodeProvider
import com.trackr.ui.components.elder.TTS_UNAVAILABLE_MESSAGE
import com.trackr.ui.util.FixedDelayPollingStrategy
import com.trackr.ui.util.PollingCoordinator
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

/**
 * v3 公交首页（重构计划第 19 步，原 `HomeViewModel`）。
 *
 * - 附近站固定 6 个；附近站 + 收藏实时每 15 秒轮询一次，由 [BusHomeRoute] 随 STARTED 开关（§4.4），
 *   恢复时距上次刷新超过一个周期就立即补刷。
 * - 定位走 [LocationRepository.bestFix]（4.5 秒窗口内取最准）。
 * - 追踪卡读所有会话（同时可以追多项，一项一张卡）；「结束」只停那一项。
 * - 乘车码按钮：「我的」里选了平台就直接打开，选了「每次询问」才弹选择。
 */
class BusHomeViewModel(
    private val settings: SettingsRepository,
    private val favorites: FavoritesRepository,
    private val realtime: RealtimeRepository,
    /** 城市弹窗的搜索记录（与顶栏搜索历史同款语义，可单条删、可全清） */
    private val cityHistory: CitySearchHistoryRepository,
    /** 首页定位写入的最近坐标（站点详情、搜索、规划页「我的位置」读它） */
    private val userLocationStore: UserLocationStore,
    /** 设备定位（首页刷新时取一次当前位置） */
    private val locationRepository: LocationRepository,
    /** 追踪服务控制（「结束追踪」、把定位喂给换乘引擎） */
    private val trackingController: TrackingController,
    /** 追踪会话（首页追踪卡） */
    private val trackingRepository: TrackingRepository,
    /** 打开支付宝 / 云闪付乘车码 */
    private val transitCodeLauncher: TransitCodeLauncher,
    private val enableAutoPolling: Boolean = true,
    /** 单调时钟（毫秒），用于判断恢复轮询时距上次刷新是否已超过一个周期；测试可替换 */
    private val elapsedRealtimeMillis: () -> Long = { ProcessMonotonicClock.elapsedMillis() },
    /** 把首页当前坐标发给搜索用（拆分前搜索直接读首页的 currentLat / currentLng）；为 null 时不发 */
    private val homeCoordinates: HomeCoordinates? = null,
    /** 长辈版卡片上的「读给我听」（§8.6） */
    private val announcer: Announcer = NoAnnouncer
) : ViewModel() {

    private val _uiState = MutableStateFlow(BusHomeUiState())
    val uiState: StateFlow<BusHomeUiState> = _uiState.asStateFlow()

    /** 改状态都走这里：数据改完立刻重算 UI 模型（没变的行沿用旧实例） */
    private inline fun edit(crossinline transform: (BusHomeUiState) -> BusHomeUiState) {
        _uiState.update { old -> BusHomeMapper.derive(old, transform(old)) }
    }

    /** 上一次实时数据（附近站 + 收藏）刷新完成的时间，基于 [elapsedRealtimeMillis]；0 表示还没刷新过 */
    @Volatile
    private var lastRealtimeRefreshAtMs: Long = 0L

    /** 恢复轮询时补发的一次立即刷新 */
    private var catchUpPollJob: Job? = null

    private var nextMessageId = 1L

    internal val pollingCoordinator = PollingCoordinator(
        scope = viewModelScope,
        strategy = FixedDelayPollingStrategy(POLLING_INTERVAL_MS),
        initialEnabled = enableAutoPolling
    ) {
        pollRealtimeOnce()
    }

    /** 轮询一次附近站点与收藏线路的实时到站（定位 / 下拉刷新进行中时跳过，避免并发重复请求） */
    private suspend fun pollRealtimeOnce() {
        val state = _uiState.value
        if (!state.isRefreshing && !state.isLoadingNearby && !state.isLocating) {
            val nearbyResult = realtime.getNearbyStops(
                state.currentCity,
                state.currentLat,
                state.currentLng,
                limit = NEARBY_STOPS_LIMIT
            )
            val favResult = realtime.refreshFavoritesRealtime()
            edit {
                it.copy(
                    nearbyStops = nearbyResult.getOrNull() ?: it.nearbyStops,
                    favoritesRealtime = favResult.getOrNull() ?: it.favoritesRealtime
                )
            }
            lastRealtimeRefreshAtMs = elapsedRealtimeMillis()
        }
    }

    val isPollingActive: Boolean
        get() = pollingCoordinator.isPollingActive

    init {
        // 首页当前坐标同步给搜索（见 HomeCoordinates）；先同步写一次默认值，搜索任何时候都读得到
        homeCoordinates?.let { target ->
            _uiState.value.let { target.update(it.currentLat, it.currentLng) }
            viewModelScope.launch {
                _uiState
                    .map { it.currentLat to it.currentLng }
                    .distinctUntilChanged()
                    .collect { (lat, lng) -> target.update(lat, lng) }
            }
        }

        // 观察数据仓库中选中的城市
        viewModelScope.launch {
            settings.selectedCity.collect { city ->
                val state = _uiState.value
                if (state.currentCity.cityId == city.cityId && state.currentCity.cityName == city.cityName) {
                    return@collect
                }
                val isSameAsLocated = city.matchesCity(state.locatedCity)
                if (isSameAsLocated) {
                    edit {
                        it.copy(
                            currentCity = city,
                            selectedProvince = city.province.ifBlank { ProvinceCityCatalog.inferProvince(city.cityName) }
                        )
                    }
                } else {
                    val coords = ProvinceCityCatalog.getCityCenterCoordinates(city.cityName)
                    edit {
                        it.copy(
                            currentCity = city,
                            selectedProvince = city.province.ifBlank { ProvinceCityCatalog.inferProvince(city.cityName) },
                            currentLat = coords.first,
                            currentLng = coords.second
                        )
                    }
                    loadNearbyStops(coords.first, coords.second, cityOverride = city)
                }
            }
        }

        // 观察城市弹窗的搜索记录（和顶栏历史同款：StateFlow 即时反映删除/清空）
        viewModelScope.launch {
            cityHistory.citySearchHistory.collect { list ->
                edit { it.copy(citySearchHistory = list) }
            }
        }

        // 收藏的车（小组件 / 快捷方式的同步在应用级收集器里做，这里只显示）
        viewModelScope.launch {
            favorites.favorites.collect { favs ->
                edit { it.copy(favorites = favs) }
            }
        }

        // 收藏合一列表（车和路线排在一起）
        viewModelScope.launch {
            favorites.favoriteItems.collect { items ->
                edit { it.copy(favoriteItems = items) }
            }
        }

        // 收藏分组；选中的分组被删了就回到「全部」
        viewModelScope.launch {
            favorites.favoriteGroups.collect { groups ->
                edit { state ->
                    val selected = state.selectedGroupId?.takeIf { id -> groups.any { it.id == id } }
                    state.copy(favoriteGroups = groups, selectedGroupId = selected)
                }
            }
        }

        // 追踪：所有会话（可以同时追多项，首页一项一张卡）+ 主会话这一程的状态
        viewModelScope.launch {
            trackingRepository.sessions.collect { sessions ->
                edit { it.copy(trackingSessions = sessions) }
            }
        }
        viewModelScope.launch {
            trackingRepository.legState.collect { session ->
                edit { it.copy(activeTracking = session) }
            }
        }

        // 观察当前引擎运行模式
        viewModelScope.launch {
            settings.engineMode.collect { mode ->
                val prevMode = _uiState.value.engineMode
                edit { it.copy(engineMode = mode) }
                if (prevMode != mode) {
                    val state = _uiState.value
                    loadNearbyStops(state.currentLat, state.currentLng)
                    refreshFavoritesRealtime()
                }
            }
        }

        // 首页右下角的「乘车码」按钮显不显示（「我的」里的开关）
        viewModelScope.launch {
            settings.showTransitCodeButton.collect { visible ->
                edit { it.copy(showTransitCodeButton = visible) }
            }
        }

        // 初始化加载支持的城市
        loadSupportedCities()

        // 刷新 GPS 定位并加载附近站点和收藏实时状态
        refreshLocationAndNearby()
    }

    /** 页面事件（导航类在 [BusHomeRoute] 里处理，到不了这里） */
    fun onEvent(event: BusHomeEvent) {
        when (event) {
            BusHomeEvent.Refresh -> refresh()
            // 「重新定位」是用户明确要重新定位的入口（附近空提示、定位提示、长辈版同款按钮）：
            // 跟定位城市走，和城市弹窗里的「重新定位」一致；自动场景（启动、下拉刷新）不变城市（Issue #6）
            BusHomeEvent.RetryLocation -> refreshLocationAndNearby(switchCity = true)
            // 上游改成按会话 / 按线停止（同时追踪多条线路）
            is BusHomeEvent.StopTracking -> stopTracking(event.sessionId, event.watchLineId)
            is BusHomeEvent.SelectGroup -> selectGroup(event.groupId)
            BusHomeEvent.TransitCodeClicked -> onTransitCodeClicked()
            is BusHomeEvent.TransitCodeChosen -> onTransitCodeChosen(event.provider, event.saveAsDefault)
            BusHomeEvent.TransitCodeSheetDismissed -> edit { it.copy(isTransitCodeSheetOpen = false) }
            is BusHomeEvent.MessageShown -> onMessageShown(event.id)
            is BusHomeEvent.ReadAloud -> readAloud(event.text)
            BusHomeEvent.OpenCityPicker -> setCityPickerOpen(true)
            BusHomeEvent.CloseCityPicker -> setCityPickerOpen(false)
            is BusHomeEvent.CitySearchChanged -> updateCitySearchQuery(event.query)
            is BusHomeEvent.ProvinceSelected -> selectProvince(event.province)
            is BusHomeEvent.CitySelected -> selectCity(event.city)
            BusHomeEvent.RelocateCity -> refreshLocationAndNearby(switchCity = true)
            BusHomeEvent.CitySearchSubmitted -> submitCitySearch()
            is BusHomeEvent.CityHistoryClicked -> updateCitySearchQuery(event.query)
            is BusHomeEvent.RemoveCityHistory -> removeCityHistory(event.query)
            BusHomeEvent.ClearCityHistory -> clearCityHistory()
            BusHomeEvent.OpenSearch,
            is BusHomeEvent.OpenLine,
            is BusHomeEvent.OpenStop,
            is BusHomeEvent.OpenRoute,
            BusHomeEvent.EditFavorites,
            BusHomeEvent.OpenSettings -> Unit
        }
    }

    private fun fallbackCities(): List<TransitCity> = listOf(
        TransitCity(cityId = "034", cityName = "上海", pinyin = "shanghai", supportSubway = true, isHot = true, province = "直辖市"),
        TransitCity(cityId = "027", cityName = "北京", pinyin = "beijing", supportSubway = true, isHot = true, province = "直辖市"),
        TransitCity(cityId = "007", cityName = "广州", pinyin = "guangzhou", supportSubway = true, isHot = true, province = "广东"),
        TransitCity(cityId = "051", cityName = "深圳", pinyin = "shenzhen", supportSubway = true, isHot = true, province = "广东"),
        TransitCity(cityId = "015", cityName = "杭州", pinyin = "hangzhou", supportSubway = true, isHot = true, province = "浙江"),
        TransitCity(cityId = "055", cityName = "南京", pinyin = "nanjing", supportSubway = true, isHot = true, province = "江苏"),
        TransitCity(cityId = "028", cityName = "武汉", pinyin = "wuhan", supportSubway = true, isHot = true, province = "湖北"),
        TransitCity(cityId = "050", cityName = "成都", pinyin = "chengdu", supportSubway = true, isHot = true, province = "四川"),
        TransitCity(cityId = "036", cityName = "重庆", pinyin = "chongqing", supportSubway = true, isHot = true, province = "直辖市"),
        TransitCity(cityId = "440", cityName = "玉林", pinyin = "yulin", supportSubway = false, isHot = true, mygolbsCityCode = "450900", province = "广西")
    )

    private fun loadSupportedCities() {
        viewModelScope.launch {
            val result = realtime.getSupportedCities()
            val list = result.getOrNull()
            if (!list.isNullOrEmpty()) {
                edit { it.copy(cities = list) }
            } else {
                edit { it.copy(cities = fallbackCities()) }
            }
        }
    }

    /**
     * 刷新定位并加载附近站点。
     *
     * 「重新定位」入口（空提示 / 定位提示 / 城市弹窗）传 [switchCity] = true；启动初始化传 false。
     */
    fun refreshLocationAndNearby(switchCity: Boolean = false) {
        refresh(switchCity)
    }

    private fun loadNearbyStops(
        lat: Double,
        lng: Double,
        cityOverride: TransitCity? = null,
        showLoading: Boolean = true
    ) {
        viewModelScope.launch {
            if (showLoading) {
                edit { it.copy(isLoadingNearby = true) }
            }
            val targetCity = cityOverride ?: _uiState.value.currentCity
            val result = realtime.getNearbyStops(targetCity, lat, lng, limit = NEARBY_STOPS_LIMIT)
            val stops = result.getOrNull().orEmpty()
            edit {
                it.copy(
                    nearbyStops = stops,
                    isLoadingNearby = false
                )
            }
        }
    }

    private fun refreshFavoritesRealtime() {
        viewModelScope.launch {
            val result = realtime.refreshFavoritesRealtime()
            val map = result.getOrNull().orEmpty()
            if (map.isNotEmpty()) {
                edit { it.copy(favoritesRealtime = map) }
            }
        }
    }

    /**
     * 整体刷新（重新定位、刷新附近公交与收藏线路实时到站）
     *
     * @param switchCity 为 true 时把城市切到定位城市（城市弹窗和空提示里的「重新定位」）。
     * 启动、下拉刷新等自动场景传 false：只更新坐标，不覆盖用户已选的城市（Issue #6）。
     * 从未存过城市（首次安装）时，即使传 false 也会切到定位城市。
     * 两种都要带上定位城市：查附近的 cityId / 城市名按定位城市给，城市只有单一数据源支持时先对齐引擎
     * （v0.0.8 修复：重新定位后附近空、必须手动选城市才出结果的根因）。
     */
    fun refresh(switchCity: Boolean = false) {
        viewModelScope.launch {
            edit { it.copy(isRefreshing = true, isLocating = true, locationError = null) }

            val favJob = launch {
                val favResult = realtime.refreshFavoritesRealtime()
                edit { it.copy(favoritesRealtime = favResult.getOrNull().orEmpty()) }
            }

            val gpsLocation = locationRepository.bestFix(forceFresh = true)

            val nearbyJob = launch {
                val (lat, lng, targetCity) = if (gpsLocation != null) {
                    val lat = gpsLocation.lat
                    val lng = gpsLocation.lng
                    userLocationStore.updateUserLocation(lat, lng)
                    // 带精度与定位时刻喂给换乘引擎，过期 / 不准的定位不参与下车站判定
                    trackingController.updateUserFix(gpsLocation)

                    // 尝试反向根据坐标解析匹配城市
                    val resolvedCity = try {
                        realtime.resolveCityByLocation(lat, lng).getOrNull()
                    } catch (_: Exception) {
                        null
                    }

                    val shouldSwitchCity = switchCity || !settings.hasSavedCity.value
                    if (resolvedCity != null && shouldSwitchCity) {
                        settings.selectCity(resolvedCity)
                        edit {
                            it.copy(
                                currentCity = resolvedCity,
                                locatedCity = resolvedCity,
                                selectedProvince = resolvedCity.province.ifBlank {
                                    ProvinceCityCatalog.inferProvince(resolvedCity.cityName)
                                },
                                currentLat = lat,
                                currentLng = lng,
                                isLocating = false,
                                locationError = null
                            )
                        }
                        alignEngineToCity(resolvedCity)
                        Triple(lat, lng, resolvedCity)
                    } else {
                        // 保留用户已选城市，只记录定位城市供城市弹窗展示「定位: X」
                        val savedCity = settings.selectedCity.value
                        edit {
                            it.copy(
                                locatedCity = resolvedCity ?: it.locatedCity,
                                currentLat = lat,
                                currentLng = lng,
                                isLocating = false,
                                locationError = null
                            )
                        }
                        alignEngineToCity(savedCity)
                        Triple(lat, lng, savedCity)
                    }
                } else {
                    val currentCity = _uiState.value.currentCity
                    val state = _uiState.value
                    val lat = if (state.currentLat > 0.0) state.currentLat else ProvinceCityCatalog.getCityCenterCoordinates(currentCity.cityName).first
                    val lng = if (state.currentLng > 0.0) state.currentLng else ProvinceCityCatalog.getCityCenterCoordinates(currentCity.cityName).second
                    edit {
                        it.copy(
                            currentLat = lat,
                            currentLng = lng,
                            isLocating = false,
                            locationError = LOCATION_UNAVAILABLE
                        )
                    }
                    Triple(lat, lng, currentCity)
                }

                val firstResult = realtime.getNearbyStops(targetCity, lat, lng, limit = NEARBY_STOPS_LIMIT)
                var stops = firstResult.getOrNull().orEmpty()

                // 人不在已选城市（如出差在外）时，GPS 坐标查不到该城市的站点：改用已选城市中心点再查一次
                if (stops.isEmpty() && gpsLocation != null && !targetCity.matchesCity(_uiState.value.locatedCity)) {
                    val center = ProvinceCityCatalog.findCityCenterCoordinates(targetCity.cityName)
                    if (center != null) {
                        stops = realtime.getNearbyStops(targetCity, center.first, center.second, limit = NEARBY_STOPS_LIMIT)
                            .getOrNull().orEmpty()
                        edit { it.copy(currentLat = center.first, currentLng = center.second) }
                    }
                }

                edit {
                    it.copy(
                        nearbyStops = stops,
                        isLoadingNearby = false
                    )
                }
                // 没连上网时附近站会空着：说一句，不然看着像「附近没站」
                if (firstResult.isFailure && stops.isEmpty()) {
                    addMessage(NEARBY_FAILED, elderText = NEARBY_FAILED_ELDER)
                }
            }

            try {
                favJob.join()
                nearbyJob.join()
            } finally {
                // 子任务失败 / 取消也要把刷新状态落回，不然附近区会永远显示「正在加载」
                edit { it.copy(isRefreshing = false, isLocating = false, isLoadingNearby = false) }
                lastRealtimeRefreshAtMs = elapsedRealtimeMillis()
            }
        }
    }

    /**
     * 城市只被单一数据源支持时把引擎切过去：车来了按 cityId 查附近、掌上公交按城市名查，
     * 城市没有车来了 id 时只有掌上公交查得到；两头都支持则保持用户的选择。
     * 选城市（[selectCity]）和重新定位（[refresh]）查附近前都先对齐（原来只有 [selectCity] 对齐，
     * 重新定位不切城市也不对齐引擎，是 v0.0.8「重新定位后附近为空」的根因）。
     */
    private fun alignEngineToCity(city: TransitCity) {
        // 自动模式下两源都会试，不需要按城市把引擎钉死成某一家（钉死反而把用户选的「自动」弄丢了）
        if (_uiState.value.engineMode == EngineMode.AUTO) return
        val target = when {
            city.cityId.isBlank() && !city.mygolbsCityCode.isNullOrBlank() -> EngineMode.MYGOLBS
            city.cityId.isNotBlank() && city.mygolbsCityCode.isNullOrBlank() -> EngineMode.CHELAILE
            // 逆地理兜底构造的空 id 城市（离线、城市表还没拉到）：车来了按 id 查必空，只有按名字查的掌上公交有机会
            city.cityId.isBlank() -> EngineMode.MYGOLBS
            else -> null
        } ?: return
        if (_uiState.value.engineMode != target) setEngineMode(target)
    }

    /**
     * 设置轮询是否激活（由 [BusHomeRoute] 的 `LifecycleStartEffect` 调：首页 entry 在组合里且 ≥ STARTED 时为 true）。
     * 从暂停恢复时，若距上次刷新已超过一个轮询周期，立即补刷一次，而不是再等一个完整周期。
     */
    fun setPollingActive(active: Boolean) {
        val resuming = active && !pollingCoordinator.isPollingActive
        pollingCoordinator.setActive(active)
        if (!active) {
            catchUpPollJob?.cancel()
            catchUpPollJob = null
            return
        }
        val lastRefresh = lastRealtimeRefreshAtMs
        if (resuming && lastRefresh > 0L &&
            elapsedRealtimeMillis() - lastRefresh >= POLLING_INTERVAL_MS &&
            catchUpPollJob?.isActive != true
        ) {
            catchUpPollJob = viewModelScope.launch { pollRealtimeOnce() }
        }
    }

    /**
     * 暂停后台轮询，安全取消当前轮询 Job
     */
    fun pausePolling() {
        pollingCoordinator.pause()
    }

    /**
     * 恢复/启动后台轮询（幂等，避免重复启动）
     */
    fun resumePolling() {
        pollingCoordinator.resume()
    }

    fun setCityPickerOpen(isOpen: Boolean) {
        edit {
            val defaultProvince = if (isOpen) {
                it.currentCity.province.ifBlank { ProvinceCityCatalog.inferProvince(it.currentCity.cityName) }
            } else {
                it.selectedProvince
            }
            it.copy(isCityPickerOpen = isOpen, citySearchQuery = "", selectedProvince = defaultProvince)
        }
    }

    fun updateCitySearchQuery(query: String) {
        edit { it.copy(citySearchQuery = query) }
    }

    fun selectProvince(province: String) {
        edit { it.copy(selectedProvince = province) }
    }

    /** 键盘「搜索」：把当前词记进城市搜索记录（不离开弹窗） */
    private fun submitCitySearch() {
        val query = _uiState.value.citySearchQuery
        if (query.isBlank()) return
        viewModelScope.launch { cityHistory.addCitySearchHistory(query) }
    }

    private fun removeCityHistory(query: String) {
        viewModelScope.launch { cityHistory.removeCitySearchHistory(query) }
    }

    private fun clearCityHistory() {
        viewModelScope.launch { cityHistory.clearCitySearchHistory() }
    }

    fun selectCity(city: TransitCity) {
        viewModelScope.launch {
            val state = _uiState.value
            // 带着搜索词选中城市：这个词进城市搜索记录（规则同顶栏「搜索后记词」）
            if (state.citySearchQuery.isNotBlank()) {
                cityHistory.addCitySearchHistory(state.citySearchQuery)
            }
            val isLocated = city.matchesCity(state.locatedCity)
            val coords = if (isLocated) {
                // 优先用真实 GPS 坐标：当前坐标可能已回退为其他城市的中心点
                userLocationStore.userLocation.value ?: Pair(state.currentLat, state.currentLng)
            } else {
                ProvinceCityCatalog.getCityCenterCoordinates(city.cityName)
            }

            // 若所选城市仅某单一数据源支持，自动联动切换至支持的引擎（和重新定位共用同一规则）
            alignEngineToCity(city)

            edit {
                it.copy(
                    currentCity = city,
                    selectedProvince = city.province.ifBlank { ProvinceCityCatalog.inferProvince(city.cityName) },
                    isCityPickerOpen = false,
                    citySearchQuery = "",
                    currentLat = coords.first,
                    currentLng = coords.second
                )
            }
            settings.selectCity(city)
            loadNearbyStops(coords.first, coords.second, cityOverride = city)
            refreshFavoritesRealtime()
        }
    }

    fun setEngineMode(mode: EngineMode) {
        settings.setEngineMode(mode)
    }

    fun toggleEngineMode() {
        // 三态循环：车来了 → 掌上公交 → 自动 → 车来了
        val nextMode = when (_uiState.value.engineMode) {
            EngineMode.MYGOLBS -> EngineMode.AUTO
            EngineMode.CHELAILE -> EngineMode.MYGOLBS
            EngineMode.AUTO -> EngineMode.CHELAILE
        }
        setEngineMode(nextMode)
    }

    /** 分组 chips：null =「全部」；不存在的分组当「全部」 */
    fun selectGroup(groupId: String?) {
        edit { state ->
            val valid = groupId?.takeIf { id -> state.favoriteGroups.any { it.id == id } }
            state.copy(selectedGroupId = valid)
        }
    }

    /**
     * 一张追踪卡上的「结束」：只停这一项，别的追踪继续。
     * @param watchLineId 一起等里的哪一条线（只去掉这条）；null = 结束整份会话
     */
    fun stopTracking(sessionId: String, watchLineId: String? = null) {
        try {
            if (watchLineId != null) {
                trackingController.removeWatchLine(sessionId, watchLineId)
            } else {
                trackingController.stopTracking(sessionId)
            }
        } catch (_: Exception) {
        }
    }

    /** 点「乘车码」：选了平台就直接打开；「每次询问」才弹选择（v3，不藏在长按里） */
    fun onTransitCodeClicked() {
        when (val provider = settings.preferredTransitCodeProvider.value) {
            TransitCodeProvider.ASK_ALWAYS -> edit { it.copy(isTransitCodeSheetOpen = true) }
            else -> transitCodeLauncher.open(provider)
        }
    }

    /** 在选择弹层里选了平台；勾了「以后都用这个」就记成默认 */
    fun onTransitCodeChosen(provider: TransitCodeProvider, saveAsDefault: Boolean) {
        edit { it.copy(isTransitCodeSheetOpen = false) }
        if (provider == TransitCodeProvider.ASK_ALWAYS) return
        if (saveAsDefault) {
            viewModelScope.launch { settings.setPreferredTransitCodeProvider(provider) }
        }
        // 打不开（没装）时 Android 实现自己会弹 Toast，这里不再重复提示
        transitCodeLauncher.open(provider)
    }

    fun onMessageShown(id: Long) {
        edit { state -> state.copy(userMessages = state.userMessages.filterNot { it.id == id }) }
    }

    private fun addMessage(text: String, elderText: String? = null) {
        val id = nextMessageId++
        edit { state -> state.copy(userMessages = state.userMessages + UserMessage(id, text, elderText)) }
    }

    /** 读一张卡片的到站；手机读不了时给一条提示 */
    private fun readAloud(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            if (!announcer.speak(text)) addMessage(TTS_UNAVAILABLE_MESSAGE)
        }
    }

    override fun onCleared() {
        super.onCleared()
        pausePolling()
    }

    companion object {
        /** 附近站固定 6 个（v3：删掉「附近站数」设置） */
        const val NEARBY_STOPS_LIMIT = 6
        const val POLLING_INTERVAL_MS = 15_000L

        const val LOCATION_UNAVAILABLE = "定位不可用，已保留当前位置"
        const val NEARBY_FAILED = "附近站没刷出来，下拉再试一次"

        /** 长辈首页没有下拉刷新：附近站空着时下面有「重新定位」按钮（第 32 步遗留） */
        const val NEARBY_FAILED_ELDER = "附近站没刷出来，点下面的「重新定位」再试一次"
    }
}
