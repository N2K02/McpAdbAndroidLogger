package dev.n2k02.mcplogger.adb

/**
 * Pure selection policy for a six-digit Android Wireless debugging pairing code.
 * The accessibility reader is restricted separately to Android Settings windows;
 * this adds a second check that the code is actually displayed in a pairing dialog.
 */
object PairingCodeUiPolicy {
    private val systemSettingsPackages = setOf(
        "com.android.settings", "com.google.android.settings", "com.android.tv.settings",
        "com.samsung.android.settings",
    )
    private val standaloneCode = Regex("""[0-9]{6}""")
    private val inlineCode = Regex("""(?<![0-9])[0-9]{6}(?![0-9])""")

    fun isSettingsPackage(packageName: String?): Boolean =
        packageName != null && packageName in systemSettingsPackages

    fun extractCode(labels: List<String>): String? {
        val isPairingDialog = labels.any {
            it.contains("pairing code", ignoreCase = true) ||
                it.contains("pair with device", ignoreCase = true) ||
                it.contains("pair device with", ignoreCase = true)
        }
        if (!isPairingDialog) return null

        // Prefer the code's own text node. Android often shows the title and the
        // code in sibling nodes; the ordinary Wireless debugging page has no code.
        labels.asSequence().map { it.trim() }.firstOrNull(standaloneCode::matches)
            ?.let { return it }

        // Some OEM dialogs render "Wi-Fi pairing code: 123456" as a single node.
        return labels.asSequence().filter {
            it.contains("pairing code", ignoreCase = true)
        }.mapNotNull { inlineCode.find(it)?.value }.firstOrNull()
    }
}
