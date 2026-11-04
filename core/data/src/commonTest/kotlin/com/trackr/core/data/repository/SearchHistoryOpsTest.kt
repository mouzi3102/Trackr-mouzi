package com.trackr.core.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [SearchHistoryOps] 的表驱动规则测试：顶栏搜索历史、城市弹窗搜索历史共用这一套「最新在前、去重、封顶」口径。
 */
class SearchHistoryOpsTest {

    @Test
    fun add_tableCases() {
        val cases = listOf(
            // (现有列表, 输入, 期望)
            Triple(emptyList<String>(), "玉林", listOf("玉林")),
            Triple(listOf("7路", "万达"), "玉林", listOf("玉林", "7路", "万达")),
            Triple(listOf("玉林", "桂林"), "玉林", listOf("玉林", "桂林")),
            Triple(listOf("Yulin"), "yulin", listOf("yulin")),
            Triple(listOf("玉林"), "  玉林  ", listOf("玉林")),
            Triple(listOf("a"), "", listOf("a")),
            Triple(listOf("a"), "   ", listOf("a")),
            Triple(listOf("a", "b"), "B", listOf("B", "a"))
        )
        for ((current, query, expected) in cases) {
            assertEquals(expected, SearchHistoryOps.add(current, query), "add($current, \"$query\")")
        }
    }

    @Test
    fun add_capsAtMaxHistory_droppingOldest() {
        val base = (1..SearchHistoryOps.MAX_HISTORY).map { "q$it" }
        val result = SearchHistoryOps.add(base, "最新")
        assertEquals(SearchHistoryOps.MAX_HISTORY, result.size)
        assertEquals("最新", result.first())
        assertFalse(result.contains("q${SearchHistoryOps.MAX_HISTORY}"), "最旧的一条应被挤出")
        assertTrue(result.contains("q1"))
    }

    @Test
    fun remove_tableCases() {
        val cases = listOf(
            Triple(listOf("玉林", "桂林"), "玉林", listOf("桂林")),
            Triple(listOf("YULIN"), "yulin", emptyList()),
            Triple(listOf("玉林"), " 玉林 ", emptyList()),
            Triple(listOf("玉林"), "不存在", listOf("玉林"))
        )
        for ((current, query, expected) in cases) {
            assertEquals(expected, SearchHistoryOps.remove(current, query), "remove($current, \"$query\")")
        }
    }

    @Test
    fun add_blankQueryReturnsSameReferenceOnlyWhenEmpty() {
        val list = listOf("玉林")
        assertSame(list, SearchHistoryOps.add(list, "  "))
    }
}
