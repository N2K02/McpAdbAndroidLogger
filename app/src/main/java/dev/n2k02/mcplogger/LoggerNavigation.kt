package dev.n2k02.mcplogger

/** Navigation contracts match mRadio: subsections return to their parent,
 * Settings returns to Home, and Back on Home is handled by Android. */
internal enum class Page(val title: String) {
    HOME("Home"), SETTINGS("Settings"), ADB("ADB connection"), BRIDGE("MCP connection"),
    LOGS("Logs"), DIAGNOSTICS("Diagnostics"), UPDATES("About & updates")
}

/**
 * Pure state machine shared by toolbar Back and Android system / predictive Back.
 * Keeping parent enables Home shortcuts (ADB and Logs) to return to Home,
 * while Settings subpages always return to the main Settings menu.
 */
internal data class LoggerNavigation(
    val page: Page = Page.HOME,
    val parent: Page = Page.SETTINGS,
) {
    init {
        require(parent == Page.HOME || parent == Page.SETTINGS) {
            "A subpage parent must be Home or Settings"
        }
    }

    val handlesBack: Boolean get() = page != Page.HOME
    val homeSelected: Boolean get() =
        page == Page.HOME || (page != Page.SETTINGS && parent == Page.HOME)
    val settingsSelected: Boolean get() = !homeSelected

    fun open(destination: Page): LoggerNavigation = when (destination) {
        Page.HOME -> LoggerNavigation(Page.HOME)
        Page.SETTINGS -> LoggerNavigation(Page.SETTINGS)
        else -> LoggerNavigation(
            page = destination,
            parent = when (page) {
                Page.HOME -> Page.HOME
                Page.SETTINGS -> Page.SETTINGS
                else -> parent
            },
        )
    }

    fun back(): LoggerNavigation = when (page) {
        Page.HOME -> this
        Page.SETTINGS -> LoggerNavigation(Page.HOME)
        else -> LoggerNavigation(parent)
    }
}
