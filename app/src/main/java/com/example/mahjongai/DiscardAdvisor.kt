package com.example.mahjongai

/**
 * 出牌决策器：向听数优先 + 精细牌效 + 牌河防守 + 听牌提示
 */
class DiscardAdvisor {

    private val shantenCalc = ShantenCalculator()

    companion object {
        private const val WILDCARD = "红中"
        private const val DEFENSE_WEIGHT = 1.5  // 河里每见一张，优先打出该牌的权重
    }

    data class Advice(
        val discard: String,
        val shantenAfter: Int,
        val waiting: List<String>,
        val reason: String,
        val riverSeen: Int,
        val remain: Int
    )

    /**
     * 完整建议：14 张手牌 + 牌河 → 最佳打出 + 向听/听牌信息
     * @param hand 手牌列表
     * @param river 牌河（已打出的牌）列表，用于防守判断
     */
    fun fullAdvice(hand: List<String>, river: List<String> = emptyList()): Advice {
        if (hand.isEmpty()) return Advice("", 8, emptyList(), "", 0, 4)

        var bestTile = hand.first()
        var bestShanten = Int.MAX_VALUE
        var bestTotal = Double.MAX_VALUE
        var bestReason = ""
        var bestSeen = 0

        for (tile in hand.distinct()) {
            val next = hand.toMutableList()
            next.remove(tile)
            val s = shantenCalc.shanten(next)
            val eff = tileEfficiency(tile, hand)
            val riverCount = river.count { it == tile }
            // 防守：河里出现越多 → 剩余越少 → 越安全 → 总分越低（优先打）
            val total = eff - riverCount * DEFENSE_WEIGHT

            if (s < bestShanten || (s == bestShanten && total < bestTotal)) {
                bestShanten = s
                bestTotal = total
                bestTile = tile
                bestSeen = riverCount
                bestReason = effReason(tile, hand)
            }
        }

        val after = hand.toMutableList()
        after.remove(bestTile)
        val waiting = if (bestShanten == 0) shantenCalc.waitingTiles(after) else emptyList()

        // 防守说明
        val reasonFull = if (river.isNotEmpty() && bestSeen >= 2) {
            "$bestReason，河里已见 $bestSeen 张(安全)"
        } else if (river.isNotEmpty() && bestSeen == 0) {
            "$bestReason，河里未见(注意放炮)"
        } else {
            bestReason
        }

        return Advice(bestTile, bestShanten, waiting, reasonFull, bestSeen, 4 - bestSeen)
    }

    /** 当前手牌向听数（-1=胡牌，0=听牌） */
    fun shanten(hand: List<String>): Int = shantenCalc.shanten(hand)

    /** 建议打出（兼容旧接口） */
    fun bestDiscard(hand: List<String>): String = fullAdvice(hand).discard

    /** 精细牌效评分：越低越该打 */
    private fun tileEfficiency(tile: String, hand: List<String>): Double {
        if (tile == WILDCARD) return 9999.0  // 红中万能牌永不主动打

        val count = hand.count { it == tile }
        if (count >= 2) return -20.0 * count  // 对子/刻子价值极高

        if (tile.length == 1) return 0.0  // 字牌，无搭子价值，优先打

        val num = tile.filter { it.isDigit() }.toIntOrNull() ?: 0
        val suit = tile.last()

        val hasPrev = hand.contains(make(num - 1, suit))
        val hasPrev2 = hand.contains(make(num - 2, suit))
        val hasNext = hand.contains(make(num + 1, suit))
        val hasNext2 = hand.contains(make(num + 2, suit))

        var score = 1.0
        if (hasNext) score += 6.0
        if (hasPrev) score += 6.0
        if (!hasNext && hasNext2) score += 3.5
        if (!hasPrev && hasPrev2) score += 3.5
        if (hasPrev && hasNext) score += 2.0

        if (num == 1 || num == 9) score -= 2.0
        if (!hasPrev && !hasNext && !hasPrev2 && !hasNext2) score -= 3.0

        return score
    }

    /** 打出理由说明 */
    private fun effReason(tile: String, hand: List<String>): String {
        if (tile == WILDCARD) return "红中万能牌保留"
        val count = hand.count { it == tile }
        if (count >= 2) return "对子/刻子价值高"
        if (tile.length == 1) return "孤张字牌"
        val num = tile.filter { it.isDigit() }.toIntOrNull() ?: 0
        val suit = tile.last()
        val hasPrev = hand.contains(make(num - 1, suit))
        val hasNext = hand.contains(make(num + 1, suit))
        val hasPrev2 = hand.contains(make(num - 2, suit))
        val hasNext2 = hand.contains(make(num + 2, suit))
        return when {
            hasPrev && hasNext -> "拆两面搭子"
            hasNext -> "拆顺向搭子"
            hasPrev -> "拆逆向搭子"
            hasNext2 -> "拆嵌张搭子"
            hasPrev2 -> "拆嵌张搭子"
            num == 1 || num == 9 -> "边张价值低"
            else -> "孤立牌"
        }
    }

    private fun make(num: Int, suit: Char): String {
        return if (num in 1..9) "$num$suit" else ""
    }
}
