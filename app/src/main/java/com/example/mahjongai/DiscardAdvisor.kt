package com.example.mahjongai

class DiscardAdvisor {

    private val shantenCalc = ShantenCalculator()

    companion object {
        private const val WILDCARD = "红中"
    }

    /**
     * 根据 14 张手牌推荐打出的牌
     * 规则：对每张候选牌模拟打出，选打出后向听数最低的牌；
     *       向听数相同则用牌效（保留对子/搭子）决胜
     */
    fun bestDiscard(hand: List<String>): String {
        if (hand.isEmpty()) return ""
        if (hand.distinct().size == 1) return hand.first()

        var bestTile = hand.first()
        var bestShanten = Int.MAX_VALUE
        var bestEff = Double.MAX_VALUE

        for (tile in hand.distinct()) {
            val next = hand.toMutableList()
            next.remove(tile)
            val s = shantenCalc.shanten(next)
            val eff = tileEfficiency(tile, hand)
            if (s < bestShanten || (s == bestShanten && eff < bestEff)) {
                bestShanten = s
                bestEff = eff
                bestTile = tile
            }
        }
        return bestTile
    }

    /** 当前手牌向听数（-1=胡牌，0=听牌） */
    fun shanten(hand: List<String>): Int = shantenCalc.shanten(hand)

    /** 牌效评分：越低越该打（作为向听数相同时的决胜） */
    private fun tileEfficiency(tile: String, hand: List<String>): Double {
        val count = hand.count { it == tile }
        // 有对子/刻子时价值高
        if (count >= 2) return -10.0 * count
        // 字牌（东南西北）价值低
        if (tile.length == 1) return 0.0
        // 与相邻牌能搭子时价值高
        var score = 1.0
        for (t in hand) {
            if (isNeighbor(tile, t)) score += 4.0
        }
        val num = tile.filter { it.isDigit() }.toIntOrNull() ?: 0
        if (num == 1 || num == 9) score -= 0.5
        return score
    }

    private fun isNeighbor(a: String, b: String): Boolean {
        if (a == WILDCARD || b == WILDCARD) return true
        if (a.last() != b.last()) return false
        val na = a.filter { it.isDigit() }.toIntOrNull() ?: return false
        val nb = b.filter { it.isDigit() }.toIntOrNull() ?: return false
        return kotlin.math.abs(na - nb) <= 1
    }
}
