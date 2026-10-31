package dev.n2k02.mcplogger

/** Home, Logs and Settings are root destinations; details belong to Home or Settings. */
internal enum class Page(val title: String) {
    HOME("Home"), LOGS("Android Logcat"), SETTINGS("Settings"),
    ADB("ADB connection"), BRIDGE("MCP connection"),
    UPDATES("About & updates")
}

/** One navigation policy for toolbar Back and Android system gesture. */
internal data class LoggerNavigation(
    val page: Page = Page.HOME,
    val parent: Page = Page.SETTINGS,
) {
    init {
        require(parent == Page.HOME || parent == Page.SETTINGS) {
            "Details must have Home or Settings as their parent"
        }
    }
    val handlesBack get() = page != Page.HOME
    val homeSelected get() = page == Page.HOME ||
        (page !in listOf(Page.HOME, Page.LOGS, Page.SETTINGS) && parent == Page.HOME)
    val logsSelected get() = page == Page.LOGS
    val settingsSelected get() = page == Page.SETTINGS ||
        (page !in listOf(Page.HOME, Page.LOGS, Page.SETTINGS) && parent == Page.SETTINGS)

    fun open(destination: Page): LoggerNavigation = when (destination) {
        Page.HOME, Page.LOGS, Page.SETTINGS -> LoggerNavigation(destination)
        else -> LoggerNavigation(destination,
            when (page) {
                Page.HOME, Page.LOGS -> Page.HOME
                Page.SETTINGS -> Page.SETTINGS
                else -> parent
            },
        )
    }

    fun back(): LoggerNavigation = when (page) {
        Page.HOME -> this
        Page.LOGS, Page.SETTINGS -> LoggerNavigation(Page.HOME)
        else -> LoggerNavigation(parent)
    }
}
