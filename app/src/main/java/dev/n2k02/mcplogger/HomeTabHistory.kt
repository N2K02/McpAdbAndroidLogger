package dev.n2k02.mcplogger

/** Bounded Home tab navigation history. Pages are added after swipe/tap settles.
 * Back pops one visit, leaving the app's global Home destination intact. */
internal object HomeTabHistory {
    private const val MAX_HISTORY = 24

    fun visit(history: List<Int>, page: Int, pageCount: Int): List<Int> {
        val current = history.ifEmpty { listOf(0) }
        if (page !in 0 until pageCount || current.last() == page) return current
        return (current + page).takeLast(MAX_HISTORY)
    }

    fun canGoBack(history: List<Int>): Boolean = history.size > 1

    fun back(history: List<Int>): List<Int> =
        if (canGoBack(history)) history.dropLast(1) else history.ifEmpty { listOf(0) }
}
