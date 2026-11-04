package com.trackr.core.model

/**
 * 城市目录搜索：一个查询词对 [TransitCity] 的「中文名 / 全拼 / 首字母 / adcode」多路匹配与排序（城市选择器用，
 * 玉林 / yulin / yl 都能命中玉林）。纯 commonMain 字符串比较，不引新依赖。
 *
 * 匹配规则（大小写不敏感、先 trim）：
 * - 中文名子串；「玉林市」这类带后缀的词先按 [ProvinceCityCatalog.cleanCityName] 剥后缀再比；
 * - 全拼前缀（[CityPinyinIndex]，目录外的城市退回 [TransitCity.pinyin] 字段）；
 * - 首字母精确 / 前缀；
 * - 纯数字查询按 adcode（掌上公交城市码，带 `45` 前缀等）或车来了 cityId 前缀。
 *
 * 排序：[TIER_EXACT] > 前缀 > 包含 > 首字母 > adcode > 省名，同档按 [ProvinceCityCatalog.PROVINCE_ORDER] 的省份位次，
 * 再同则保持传入顺序（稳定）。
 */
object CityCatalogSearch {

    const val TIER_EXACT = 0
    const val TIER_PREFIX = 1
    const val TIER_CONTAINS = 2
    const val TIER_INITIALS_EXACT = 3
    const val TIER_INITIALS_PREFIX = 4
    const val TIER_ADCODE = 5

    /** 省份名命中（沿用旧弹窗「也能按省筛」的能力，排在最后） */
    const val TIER_PROVINCE = 6

    /** 查询词的归一化结果：原词小写、剥行政区划后缀的小写、是否纯数字 */
    private class NormalizedQuery(val lower: String, val cleanLower: String, val isDigits: Boolean)

    private fun normalize(query: String): NormalizedQuery? {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return null
        // 连写匹配时撇号/空格不是有效字符：网络数据里 六安 常写成 "Lu'an"，用户也可能这么输
        val lower = trimmed.lowercase().filterNot { it == '\'' || it == ' ' }
        return NormalizedQuery(lower, ProvinceCityCatalog.cleanCityName(trimmed).lowercase(), trimmed.all { it.isDigit() })
    }

    /** 目录内城市用 [CityPinyinIndex]；目录外（网络城市）用它自带的 pinyin 字段（无音节分隔时推不出首字母） */
    private class PinyinForm(val full: String, val initials: String)

    private fun pinyinOf(city: TransitCity): PinyinForm {
        val segments = CityPinyinIndex.segmentOf(city.cityName)
        if (segments != null) return PinyinForm(CityPinyinIndex.joinSegments(segments), CityPinyinIndex.initialsFromSegments(segments))
        val fallback = city.pinyin.trim().lowercase().filterNot { it == '\'' || it == ' ' }
        if (fallback.isEmpty()) return PinyinForm("", "")
        if (fallback.contains('-')) return PinyinForm(CityPinyinIndex.joinSegments(fallback), CityPinyinIndex.initialsFromSegments(fallback))
        return PinyinForm(fallback, "")
    }

    /** 单个城市对查询词的最佳匹配档位；不匹配返回 null。空白查询返回 null（[filter] 会整体短路，不会走到这里） */
    fun tierOf(city: TransitCity, query: String): Int? {
        val q = normalize(query) ?: return null
        val name = city.cityName.trim().lowercase()
        val cleanName = ProvinceCityCatalog.cleanCityName(city.cityName).lowercase()
        val pinyin = pinyinOf(city)
        val candidates = if (cleanName == name) listOf(name) else listOf(name, cleanName)
        val queryForms = if (q.cleanLower == q.lower) listOf(q.lower) else listOf(q.lower, q.cleanLower)

        if (candidates.any { c -> queryForms.any { c == it } }) return TIER_EXACT
        if (candidates.any { c -> queryForms.any { c.startsWith(it) } } ||
            (pinyin.full.isNotEmpty() && pinyin.full.startsWith(q.lower))
        ) return TIER_PREFIX
        if (candidates.any { c -> queryForms.any { c.contains(it) } } ||
            (pinyin.full.isNotEmpty() && pinyin.full.contains(q.lower))
        ) return TIER_CONTAINS
        if (pinyin.initials.isNotEmpty()) {
            if (pinyin.initials == q.lower) return TIER_INITIALS_EXACT
            if (pinyin.initials.startsWith(q.lower)) return TIER_INITIALS_PREFIX
        }
        if (q.isDigits || q.lower.any { it.isDigit() }) {
            val code = city.mygolbsCityCode?.trim().orEmpty()
            if (code.isNotEmpty() && code.startsWith(q.lower)) return TIER_ADCODE
            if (city.cityId.isNotBlank() && city.cityId.startsWith(q.lower)) return TIER_ADCODE
        }
        if (!q.isDigits) {
            val prov = (city.province.ifBlank { ProvinceCityCatalog.inferProvince(city.cityName) }).lowercase()
            if (prov.isNotEmpty() && prov != "其他" &&
                (prov.contains(q.lower) || prov.contains(q.cleanLower))
            ) return TIER_PROVINCE
        }
        return null
    }

    /** 按查询词过滤并排序；查询词空白时原样返回 */
    fun filter(cities: List<TransitCity>, query: String): List<TransitCity> {
        if (query.isBlank()) return cities
        val keys = ArrayList<Triple<Int, Int, Int>>(cities.size)
        for ((index, city) in cities.withIndex()) {
            val tier = tierOf(city, query) ?: continue
            keys.add(Triple(tier, provinceIndex(city), index))
        }
        keys.sortWith(compareBy({ it.first }, { it.second }, { it.third }))
        return keys.map { cities[it.third] }
    }

    private fun provinceIndex(city: TransitCity): Int {
        val prov = city.province.ifBlank { ProvinceCityCatalog.inferProvince(city.cityName) }
        return ProvinceCityCatalog.provinceOrderIndex(prov)
    }
}
