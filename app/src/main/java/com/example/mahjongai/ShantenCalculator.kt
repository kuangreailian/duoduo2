package com.example.mahjongai

import kotlin.math.max
import kotlin.math.min

/**
 * 向听数计算器
 * 向听数含义：-1=已经胡牌，0=听牌，1=N向听
 * 红中作为万能牌（癞子），可替代任意牌
 */
class ShantenCalculator {

    private val memo = HashMap<String, Int>()

    companion object {
        private const val JOKER_IDX = 29

        fun tileToIndex(tile: String): Int {
            return when (tile) {
                "1条" -> 0; "2条" -> 1; "3条" -> 2; "4条" -> 3; "5条" -> 4
                "6条" -> 5; "7条" -> 6; "8条" -> 7; "9条" -> 8
                "1万" -> 9; "2万" -> 10; "3万" -> 11; "4万" -> 12; "5万" -> 13
                "6万" -> 14; "7万" -> 15; "8万" -> 16; "9万" -> 17
                "1筒" -> 18; "2筒" -> 19; "3筒" -> 20; "4筒" -> 21; "5筒" -> 22
                "6筒" -> 23; "7筒" -> 24; "8筒" -> 25; "9筒" -> 26
                "东" -> 27; "北" -> 28; "红中" -> 29; "南" -> 30; "西" -> 31
                else -> -1
            }
        }
    }

    /**
     * 计算一组牌的向听数（支持红中癞子）
     * @param tiles 牌列表，如 ["1万","1万","红中","东",...]
     */
    fun shanten(tiles: List<String>): Int {
        val c = IntArray(34)
        var jokers = 0
        for (t in tiles) {
            val idx = tileToIndex(t)
            if (idx == JOKER_IDX) jokers++ else c[idx]++
        }
        if (jokers == 0) return standard(c)

        // 带癞子：枚举癞子替换为候选牌，取最小向听
        val candidates = buildCandidates(c, jokers)
        var best = 8
        enumerate(c, jokers, candidates) { cc ->
            val s = standard(cc)
            if (s < best) best = s
        }
        return best
    }

    /** 无癞子标准向听数 */
    private fun standard(c: IntArray): Int {
        var best = 8
        var hasPair = false
        // 枚举雀头
        for (i in 0 until 34) {
            if (c[i] >= 2) {
                hasPair = true
                c[i] -= 2
                memo.clear()
                best = min(best, 8 - calcMeld(c, 0, 0, 0))
                c[i] += 2
            }
        }
        if (!hasPair) {
            memo.clear()
            best = min(best, 9 - calcMeld(c, 0, 0, 0))
        }
        best = min(best, sevenPairs(c))
        return best
    }

    /** 计算面子(2) + 搭子(1) 的最大得分，m+t<=4 */
    private fun calcMeld(c: IntArray, start: Int, m: Int, t: Int): Int {
        var i = start
        while (i < 34 && c[i] == 0) i++
        if (i >= 34) {
            val mm = min(m, 4)
            val tt = min(t, 4 - mm)
            return 2 * mm + tt
        }
        val key = "$i|$m|$t|" + c.joinToString(",")
        memo[key]?.let { return it }

        var best = calcMeld(c, i + 1, m, t)

        // 刻子
        if (m < 4 && c[i] >= 3) {
            c[i] -= 3
            best = max(best, calcMeld(c, i, m + 1, t))
            c[i] += 3
        }
        // 顺子（数牌）
        if (m < 4 && i < 27 && i % 9 <= 6 && c[i] > 0 && c[i + 1] > 0 && c[i + 2] > 0) {
            c[i]--; c[i + 1]--; c[i + 2]--
            best = max(best, calcMeld(c, i, m + 1, t))
            c[i]++; c[i + 1]++; c[i + 2]++
        }
        // 搭子：对子
        if (m + t < 4 && c[i] >= 2) {
            c[i] -= 2
            best = max(best, calcMeld(c, i, m, t + 1))
            c[i] += 2
        }
        // 搭子：邻张（两面/边张）
        if (m + t < 4 && i < 27 && i % 9 <= 7 && c[i] > 0 && c[i + 1] > 0) {
            c[i]--; c[i + 1]--
            best = max(best, calcMeld(c, i, m, t + 1))
            c[i]++; c[i + 1]++
        }
        // 搭子：嵌张
        if (m + t < 4 && i < 27 && i % 9 <= 6 && c[i] > 0 && c[i + 2] > 0) {
            c[i]--; c[i + 2]--
            best = max(best, calcMeld(c, i, m, t + 1))
            c[i]++; c[i + 2]++
        }
        memo[key] = best
        return best
    }

    private fun sevenPairs(c: IntArray): Int {
        var pairs = 0
        for (i in 0 until 34) {
            if (c[i] >= 2) pairs++
            if (c[i] == 4) pairs++
        }
        return max(0, 6 - pairs)
    }

    /** 癞子替换候选：手牌已有牌及相邻牌，癞子多时缩小范围 */
    private fun buildCandidates(c: IntArray, jokers: Int): List<Int> {
        val set = LinkedHashSet<Int>()
        for (i in 0 until 34) {
            if (c[i] > 0) {
                set.add(i)
                if (i < 27) {
                    if (i % 9 > 0) set.add(i - 1)
                    if (i % 9 < 8) set.add(i + 1)
                }
            }
        }
        // 字牌兜底
        if (set.isEmpty()) {
            for (i in 0 until 34) set.add(i)
        }
        var list = set.toList()
        // 癞子多时限制候选数量，防止组合爆炸
        if (jokers >= 3 && list.size > 10) list = list.take(10)
        if (jokers >= 4 && list.size > 7) list = list.take(7)
        return list
    }

    private fun enumerate(c: IntArray, jokers: Int, candidates: List<Int>, emit: (IntArray) -> Unit) {
        if (jokers == 0) {
            emit(c)
            return
        }
        for (idx in candidates) {
            c[idx]++
            enumerate(c, jokers - 1, candidates, emit)
            c[idx]--
        }
    }
}
