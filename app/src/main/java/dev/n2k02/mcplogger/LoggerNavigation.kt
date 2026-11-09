package dev.n2k02.mcplogger

/** Main navigation: Home, MCP Audits, Logs, Settings, Quit. */
internal enum class Page(val title: String) {
    HOME("Home"), MCP_AUDITS("MCP audits"), LOGS("Android Logcat"),
    SETTINGS("Settings"), ADB("ADB connection"), BRIDGE("MCP connection"),
    UPDATES("About & updates")
}

/** Settings details retain their parent; Audits is an independent root page. */
internal data class LoggerNavigation(
    val page: Page = Page.HOME,
    val parent: Page = Page.SETTINGS,
) {
    init {
        require(parent == Page.HOME || parent == Page.SETTINGS)
    }
    private val roots = setOf(Page.HOME, Page.MCP_AUDITS, Page.LOGS, Page.SETTINGS)
    val handlesBack get() = page != Page.HOME
    val homeSelected get() = page == Page.HOME ||
        (page !in roots && parent == Page.HOME)
    val auditsSelected get() = page == Page.MCP_AUDITS
    val logsSelected get() = page == Page.LOGS
    val settingsSelected get() = page == Page.SETTINGS ||
        (page !in roots && parent == Page.SETTINGS)

    fun open(destination: Page): LoggerNavigation = when (destination) {
        in roots -> LoggerNavigation(destination)
        else -> LoggerNavigation(destination,
            when (page) {
                Page.HOME, Page.MCP_AUDITS, Page.LOGS -> Page.HOME
                Page.SETTINGS -> Page.SETTINGS
                else -> parent
            })
    }

    fun back(): LoggerNavigation = when (page) {
        Page.HOME -> this
        Page.MCP_AUDITS, Page.LOGS, Page.SETTINGS -> LoggerNavigation(Page.HOME)
        else -> LoggerNavigation(parent)
    }
}
