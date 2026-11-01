package dev.n2k02.mcplogger.core

/** UID/package listing is expensive. Log queries can reuse a short-lived list;
 * explicit installed-app requests still refresh immediately. */
internal object PackageRefreshPolicy {
    const val MAX_AGE_MS = 120_000L

    fun shouldRefresh(nowMs: Long, lastRefreshMs: Long, populated: Boolean, force: Boolean): Boolean =
        force || !populated || lastRefreshMs < 0 || nowMs < lastRefreshMs ||
            nowMs - lastRefreshMs >= MAX_AGE_MS
}
