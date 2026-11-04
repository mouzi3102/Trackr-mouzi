package com.trackr.feature.bus.citypicker

import com.trackr.core.model.CityCatalogSearch
import com.trackr.core.model.CityPinyinIndex
import com.trackr.core.model.ProvinceCityCatalog
import com.trackr.core.model.TransitCity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 城市目录拼音表 + 多路匹配（城市选择器搜索用）的纯逻辑测试。
 */
class CityCatalogSearchTest {

    private fun city(
        name: String,
        adcode: String? = null,
        clId: String = "1",
        pinyin: String = "",
        province: String = ""
    ) = TransitCity(cityId = clId, cityName = name, pinyin = pinyin, mygolbsCityCode = adcode, province = province)

    @Test
    fun pinyinTable_coversEveryCatalogCity_withoutGaps() {
        val names = ProvinceCityCatalog.ALL_CITY_NAMES
        assertTrue(names.size >= 480, "目录应收录 480+ 城市，实际 ${names.size}")
        assertEquals(ProvinceCityCatalog.PROVINCE_ORDER.sumOf { ProvinceCityCatalog.citiesOf(it).size }, names.size)
        val gaps = names.filter { CityPinyinIndex.segmentOf(it).isNullOrBlank() }
        assertTrue(gaps.isEmpty(), "缺失拼音条目: $gaps")
        val illegal = names.filterNot { CityPinyinIndex.segmentOf(it)?.matches(Regex("^[a-z]+(-[a-z]+)*$")) ?: false }
        assertTrue(illegal.isEmpty(), "非法拼音格式: $illegal")
        assertEquals(names.size, CityPinyinIndex.size, "拼音表不应有目录外的多余条目")
    }

    @Test
    fun fullPinyinAndInitials_sampleReadings() {
        // 多音字关键读音
        assertEquals("yulin", CityPinyinIndex.fullPinyin("玉林"))
        assertEquals("yl", CityPinyinIndex.initialsOf("玉林"))
        assertEquals("chongqing", CityPinyinIndex.fullPinyin("重庆"))
        assertEquals("cq", CityPinyinIndex.initialsOf("重庆"))
        assertEquals("yueqing", CityPinyinIndex.fullPinyin("乐清"))
        assertEquals("changchun", CityPinyinIndex.fullPinyin("长春"))
        assertEquals("luan", CityPinyinIndex.fullPinyin("六安"))
        assertEquals("shifang", CityPinyinIndex.fullPinyin("什邡"))
        assertEquals("sf", CityPinyinIndex.initialsOf("什邡"))
    }

    @Test
    fun search_玉林_tableDrivenQueries() {
        val cities = listOf(city("玉林", "450900", clId = "440"), city("桂林"), city("南宁"))
        for (query in listOf("玉林", "玉林市", "yulin", "YULIN", " YuLin ", "yl", "YL", "4509")) {
            val hit = CityCatalogSearch.filter(cities, query).map { it.cityName }
            assertTrue("玉林" in hit, "「$query」应命中玉林，实际 $hit")
        }
    }

    @Test
    fun search_重庆_and_yun_prefix() {
        val cities = listOf(city("重庆"), city("云浮"), city("玉林"))
        assertEquals(listOf("重庆"), CityCatalogSearch.filter(cities, "cq").map { it.cityName })
        assertEquals("云浮", CityCatalogSearch.filter(cities, "YUN").first().cityName)
    }

    @Test
    fun search_ranking_prefixBeatsContains_thenProvinceOrder() {
        val cities = listOf(
            city("玉林"),      // yulin：包含 lin
            city("桂林"),      // guilin：包含 lin
            city("临海"),      // linhai：前缀 lin
            city("吉林")       // jilin：包含 lin
        )
        val ordered = CityCatalogSearch.filter(cities, "lin").map { it.cityName }
        assertEquals("临海", ordered.first())
        assertEquals(setOf("玉林", "桂林", "吉林"), ordered.drop(1).toSet())
        // 同档按省份展示顺序：广西（玉林/桂林，保持传入序）在吉林（省）前
        assertEquals(listOf("临海", "玉林", "桂林", "吉林"), ordered)
    }

    @Test
    fun search_exactNameWins() {
        val cities = listOf(city("吉林"), city("玉林"))
        assertEquals("吉林", CityCatalogSearch.filter(cities, "吉林").first().cityName)
    }

    @Test
    fun search_blankQueryReturnsInputUnchanged() {
        val cities = listOf(city("玉林"), city("桂林"))
        assertEquals(cities, CityCatalogSearch.filter(cities, "   "))
    }

    @Test
    fun search_fallsBackToTransitCityPinyin_whenNotInCatalog() {
        val cities = listOf(city("仙乡", clId = "9527", pinyin = "xianxiang"))
        assertEquals(cities, CityCatalogSearch.filter(cities, "xianx"))
        // 无音节分隔的外部 pinyin 推不出首字母，但全拼前缀/包含可用
        assertTrue(CityCatalogSearch.filter(cities, "xx").isEmpty())
    }

    @Test
    fun provinceQuery_stillMatchesAsLowestTier() {
        val cities = listOf(city("玉林"), city("广州"))
        val hit = CityCatalogSearch.filter(cities, "广西").map { it.cityName }
        assertEquals(listOf("玉林"), hit)
    }
}
