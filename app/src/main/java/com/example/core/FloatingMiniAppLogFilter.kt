package com.example.core

/**
 * Pure log-filtering helper for the Floating Mini-apps ecosystem.
 *
 * Filters raw diagnostic and crash log text to retain only entries belonging
 * to the floating mini-app framework, preserving complete multiline exception
 * stack traces and uncaught crash report blocks.
 */
object FloatingMiniAppLogFilter {

    const val EMPTY_STATE_MESSAGE = "No floating mini-app logs found."

    /**
     * Dedicated component tags that exclusively belong to floating mini-apps.
     */
    val MINI_APP_COMPONENT_TAGS = setOf(
        "FloatingMiniAppBridge",
        "FloatingMiniAppWindow",
        "FloatingMiniAppLauncherBubble",
        "HeavyFloatingHost",
        "HeavyFloatingHostService",
        "TestNotesImeDebug"
    )

    private val MINI_APP_TYPE_MARKERS = listOf(
        "test_notes",
        "speed_monitor",
        "ebook_reader"
    )

    private val MINI_APP_STACK_MARKERS = listOf(
        "FloatingMiniAppBridge",
        "FloatingMiniAppWindow",
        "FloatingMiniAppLauncherBubble",
        "HeavyFloatingHost",
        "HeavyFloatingHostService",
        "TestNotesImeDebug",
        "TestNotesMiniApp",
        "SpeedMonitorMiniApp",
        "EBookReaderMiniApp",
        "DurableMiniAppStore",
        "com.example.feature.floating."
    )

    /**
     * Filters [rawLogs] to display only floating mini-app records.
     * When [enabled] is false, returns [rawLogs] verbatim.
     * When [enabled] is true and no records match, returns [EMPTY_STATE_MESSAGE].
     */
    fun filter(rawLogs: String, enabled: Boolean): String {
        if (!enabled) return rawLogs
        if (rawLogs.isBlank() || rawLogs.startsWith("No records in ")) {
            return EMPTY_STATE_MESSAGE
        }

        val blocks = splitIntoBlocks(rawLogs)
        val matchingBlocks = blocks.filter { isMiniAppLogEntry(it) }

        return if (matchingBlocks.isEmpty()) {
            EMPTY_STATE_MESSAGE
        } else {
            matchingBlocks.joinToString("\n\n")
        }
    }

    /**
     * Evaluates whether a single log entry block belongs to the floating mini-app framework.
     */
    fun isMiniAppLogEntry(block: String): Boolean {
        // 1. Crash report block analysis
        if (block.contains("UNCAUGHT EXCEPTION CRASH REPORT")) {
            return isMiniAppCrashReport(block)
        }

        // 2. Component tag analysis from standard log line: [timestamp] [process] [thread] [component] message
        val firstLine = block.lines().firstOrNull() ?: block
        val component = extractComponent(firstLine)
        if (component != null) {
            val cleanComponent = component.removePrefix("ERROR:").removePrefix("CRASH:")
            if (MINI_APP_COMPONENT_TAGS.contains(cleanComponent)) {
                return true
            }
        }

        // 3. Contextual mini-app check: instance ID, miniapp_ consumer tag, mini-app type, explicit markers
        return containsContextualMiniAppMarker(block)
    }

    /**
     * Extracts component name from a standard log header line:
     * "[2026-11-05 10:00:00.000] [process] [thread] [component] message"
     */
    private fun extractComponent(headerLine: String): String? {
        val regex = Regex("""^\[[^\]]+\]\s+\[[^\]]+\]\s+\[[^\]]+\]\s+\[([^\]]+)\]""")
        val match = regex.find(headerLine)
        return match?.groupValues?.get(1)
    }

    /**
     * Identifies mini-app context within shared components (FloatingWindowManager, HeavyPageHostManager, etc.).
     */
    private fun containsContextualMiniAppMarker(text: String): Boolean {
        // Explicit mini-app identifiers and consumer prefixes
        if (text.contains("miniapp_", ignoreCase = true) ||
            text.contains("mini_app_", ignoreCase = true) ||
            text.contains("floating_mini_app", ignoreCase = true) ||
            text.contains("FloatingMiniApp", ignoreCase = false)
        ) {
            return true
        }

        // Specific mini-app types when anchored as identifiers or parameters
        for (type in MINI_APP_TYPE_MARKERS) {
            if (text.contains(type, ignoreCase = true)) {
                if (text.contains("type=$type", ignoreCase = true) ||
                    text.contains("app_type=$type", ignoreCase = true) ||
                    text.contains("appType=$type", ignoreCase = true) ||
                    text.contains("id=$type", ignoreCase = true) ||
                    text.contains("instanceId=$type", ignoreCase = true) ||
                    text.contains("target=$type", ignoreCase = true) ||
                    text.contains("targetId=$type", ignoreCase = true) ||
                    text.contains("id=miniapp_$type", ignoreCase = true) ||
                    text.contains("\"$type\"") ||
                    text.contains("'$type'")
                ) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * Inspects uncaught crash report blocks for mini-app classes in stack trace or message.
     */
    private fun isMiniAppCrashReport(block: String): Boolean {
        for (marker in MINI_APP_STACK_MARKERS) {
            if (block.contains(marker)) {
                return true
            }
        }
        return containsContextualMiniAppMarker(block)
    }

    /**
     * Splits raw log text into discrete blocks.
     * Preserves multiline stack traces and uncaught crash report blocks within their parent entry.
     */
    fun splitIntoBlocks(rawText: String): List<String> {
        if (rawText.isBlank()) return emptyList()
        val lines = rawText.lines()
        val blocks = mutableListOf<String>()
        val currentBlock = StringBuilder()

        val timestampRegex = Regex("""^\[\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}\]""")
        var inCrashReport = false

        for (line in lines) {
            val trimmed = line.trim()
            val isCrashDelimiter = trimmed.startsWith("================================================================")
            val isTimestamp = timestampRegex.containsMatchIn(line)

            if (isCrashDelimiter) {
                if (!inCrashReport) {
                    // Flush existing block if any before starting crash block
                    if (currentBlock.isNotEmpty()) {
                        val b = currentBlock.toString().trim()
                        if (b.isNotEmpty()) blocks.add(b)
                        currentBlock.clear()
                    }
                    inCrashReport = true
                    currentBlock.append(line).append("\n")
                } else {
                    // End of crash block
                    currentBlock.append(line)
                    inCrashReport = false
                    val b = currentBlock.toString().trim()
                    if (b.isNotEmpty()) blocks.add(b)
                    currentBlock.clear()
                }
            } else if (isTimestamp && !inCrashReport) {
                if (currentBlock.isNotEmpty()) {
                    val b = currentBlock.toString().trim()
                    if (b.isNotEmpty()) blocks.add(b)
                    currentBlock.clear()
                }
                currentBlock.append(line).append("\n")
            } else {
                currentBlock.append(line).append("\n")
            }
        }

        if (currentBlock.isNotEmpty()) {
            val b = currentBlock.toString().trim()
            if (b.isNotEmpty()) blocks.add(b)
        }

        return blocks
    }
}
