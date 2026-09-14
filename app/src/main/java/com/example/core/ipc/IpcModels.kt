package com.example.core.ipc

/**
 * Supported command types from Main to Heavy process.
 */
enum class HeavyCommandType {
    START_OPERATION,
    STOP_OPERATION,
    PAUSE_OPERATION,
    RESUME_OPERATION,
    UPDATE_CONFIG,
    PING,
    CALL_STATE_CHANGED,
    REQUEST_CALL_RECORDER,
    SHOW_WELCOME,
    CUSTOM
}

/**
 * Immutable command sent from Main to Heavy process.
 */
data class HeavyCommand(
    val commandId: String,
    val type: HeavyCommandType,
    val targetId: String? = null,
    val payload: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): String {
        return buildString {
            append("{")
            append("\"commandId\":").append(IpcJsonUtils.quote(commandId)).append(",")
            append("\"type\":").append(IpcJsonUtils.quote(type.name)).append(",")
            append("\"targetId\":").append(IpcJsonUtils.quoteNullable(targetId)).append(",")
            append("\"payload\":").append(IpcJsonUtils.mapToJson(payload)).append(",")
            append("\"timestamp\":").append(timestamp)
            append("}")
        }
    }

    companion object {
        fun fromJson(json: String): HeavyCommand {
            val map = IpcJsonUtils.parseObject(json)
            val commandId = map["commandId"] as? String ?: ""
            val typeStr = map["type"] as? String ?: HeavyCommandType.CUSTOM.name
            val type = try {
                HeavyCommandType.valueOf(typeStr)
            } catch (e: Exception) {
                HeavyCommandType.CUSTOM
            }
            val targetId = map["targetId"] as? String
            @Suppress("UNCHECKED_CAST")
            val payload = (map["payload"] as? Map<String, String>) ?: emptyMap()
            val timestamp = (map["timestamp"] as? Number)?.toLong() ?: 0L
            return HeavyCommand(commandId, type, targetId, payload, timestamp)
        }
    }
}

/**
 * Supported event types from Heavy process to Main process.
 */
enum class HeavyEventType {
    LIFECYCLE_CHANGED,
    STATE_UPDATED,
    OPERATION_FINISHED,
    ERROR_REPORTED,
    HEARTBEAT,
    CUSTOM
}

/**
 * Immutable event reported by Heavy process to Main process.
 */
data class HeavyEvent(
    val eventId: String,
    val type: HeavyEventType,
    val sourceId: String? = null,
    val payload: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): String {
        return buildString {
            append("{")
            append("\"eventId\":").append(IpcJsonUtils.quote(eventId)).append(",")
            append("\"type\":").append(IpcJsonUtils.quote(type.name)).append(",")
            append("\"sourceId\":").append(IpcJsonUtils.quoteNullable(sourceId)).append(",")
            append("\"payload\":").append(IpcJsonUtils.mapToJson(payload)).append(",")
            append("\"timestamp\":").append(timestamp)
            append("}")
        }
    }

    companion object {
        fun fromJson(json: String): HeavyEvent {
            val map = IpcJsonUtils.parseObject(json)
            val eventId = map["eventId"] as? String ?: ""
            val typeStr = map["type"] as? String ?: HeavyEventType.CUSTOM.name
            val type = try {
                HeavyEventType.valueOf(typeStr)
            } catch (e: Exception) {
                HeavyEventType.CUSTOM
            }
            val sourceId = map["sourceId"] as? String
            @Suppress("UNCHECKED_CAST")
            val payload = (map["payload"] as? Map<String, String>) ?: emptyMap()
            val timestamp = (map["timestamp"] as? Number)?.toLong() ?: 0L
            return HeavyEvent(eventId, type, sourceId, payload, timestamp)
        }
    }
}

/**
 * Minimal immutable snapshot of Main process state provided on-demand to Heavy.
 *
 * Main remains the authoritative source of truth: Heavy owns only its own heavy-process state
 * and must never treat this snapshot as an editable runtime hierarchy.
 */
data class MainStateSnapshot(
    val snapshotId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isScreenOn: Boolean = true,
    val isEditMode: Boolean = false,
    val activeContainerId: String? = null,
    val activePageId: String? = null,
    val config: Map<String, String> = emptyMap()
) {
    fun toJson(): String {
        return buildString {
            append("{")
            append("\"snapshotId\":").append(IpcJsonUtils.quote(snapshotId)).append(",")
            append("\"timestamp\":").append(timestamp).append(",")
            append("\"isScreenOn\":").append(isScreenOn).append(",")
            append("\"isEditMode\":").append(isEditMode).append(",")
            append("\"activeContainerId\":").append(IpcJsonUtils.quoteNullable(activeContainerId)).append(",")
            append("\"activePageId\":").append(IpcJsonUtils.quoteNullable(activePageId)).append(",")
            append("\"config\":").append(IpcJsonUtils.mapToJson(config))
            append("}")
        }
    }

    companion object {
        fun fromJson(json: String): MainStateSnapshot {
            val map = IpcJsonUtils.parseObject(json)
            val snapshotId = map["snapshotId"] as? String ?: ""
            val timestamp = (map["timestamp"] as? Number)?.toLong() ?: 0L
            val isScreenOn = (map["isScreenOn"] as? Boolean) ?: true
            val isEditMode = (map["isEditMode"] as? Boolean) ?: false
            val activeContainerId = map["activeContainerId"] as? String
            val activePageId = map["activePageId"] as? String
            @Suppress("UNCHECKED_CAST")
            val config = (map["config"] as? Map<String, String>) ?: emptyMap()
            return MainStateSnapshot(
                snapshotId,
                timestamp,
                isScreenOn,
                isEditMode,
                activeContainerId,
                activePageId,
                config
            )
        }
    }
}

/**
 * Actions that the Heavy process can request the Main process to execute.
 */
enum class MainActionType {
    CLOSE_WINDOW,
    SHOW_TOAST,
    TRIGGER_ACTION,
    OPEN_CONTAINER,
    DISMISS_ALL,
    CUSTOM
}

/**
 * Immutable request from Heavy asking Main to perform a scoped action.
 */
data class MainCommandRequest(
    val requestId: String,
    val action: MainActionType,
    val targetId: String? = null,
    val parameters: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): String {
        return buildString {
            append("{")
            append("\"requestId\":").append(IpcJsonUtils.quote(requestId)).append(",")
            append("\"action\":").append(IpcJsonUtils.quote(action.name)).append(",")
            append("\"targetId\":").append(IpcJsonUtils.quoteNullable(targetId)).append(",")
            append("\"parameters\":").append(IpcJsonUtils.mapToJson(parameters)).append(",")
            append("\"timestamp\":").append(timestamp)
            append("}")
        }
    }

    companion object {
        fun fromJson(json: String): MainCommandRequest {
            val map = IpcJsonUtils.parseObject(json)
            val requestId = map["requestId"] as? String ?: ""
            val actionStr = map["action"] as? String ?: MainActionType.CUSTOM.name
            val action = try {
                MainActionType.valueOf(actionStr)
            } catch (e: Exception) {
                MainActionType.CUSTOM
            }
            val targetId = map["targetId"] as? String
            @Suppress("UNCHECKED_CAST")
            val parameters = (map["parameters"] as? Map<String, String>) ?: emptyMap()
            val timestamp = (map["timestamp"] as? Number)?.toLong() ?: 0L
            return MainCommandRequest(requestId, action, targetId, parameters, timestamp)
        }
    }
}

/**
 * Standard error codes for Main ↔ Heavy IPC operations.
 */
enum class IpcErrorCode(val code: Int) {
    OK(0),
    HEAVY_UNAVAILABLE(1),
    TIMEOUT(2),
    MARSHAL_ERROR(3),
    REJECTED(4),
    DEAD_BINDER(5),
    UNKNOWN_ERROR(99);

    companion object {
        fun fromCode(code: Int): IpcErrorCode {
            return entries.firstOrNull { it.code == code } ?: UNKNOWN_ERROR
        }
    }
}

/**
 * Standard response / acknowledgment model across the IPC boundary.
 */
data class IpcResult(
    val success: Boolean,
    val errorCode: IpcErrorCode = IpcErrorCode.OK,
    val message: String = "",
    val data: Map<String, String> = emptyMap()
) {
    fun toJson(): String {
        return buildString {
            append("{")
            append("\"success\":").append(success).append(",")
            append("\"errorCode\":").append(errorCode.code).append(",")
            append("\"message\":").append(IpcJsonUtils.quote(message)).append(",")
            append("\"data\":").append(IpcJsonUtils.mapToJson(data))
            append("}")
        }
    }

    companion object {
        fun success(message: String = "", data: Map<String, String> = emptyMap()): IpcResult {
            return IpcResult(true, IpcErrorCode.OK, message, data)
        }

        fun error(code: IpcErrorCode, message: String): IpcResult {
            return IpcResult(false, code, message)
        }

        fun fromJson(json: String): IpcResult {
            val map = IpcJsonUtils.parseObject(json)
            val success = (map["success"] as? Boolean) ?: false
            val errCodeInt = (map["errorCode"] as? Number)?.toInt() ?: 0
            val errorCode = IpcErrorCode.fromCode(errCodeInt)
            val message = map["message"] as? String ?: ""
            @Suppress("UNCHECKED_CAST")
            val data = (map["data"] as? Map<String, String>) ?: emptyMap()
            return IpcResult(success, errorCode, message, data)
        }
    }
}

/**
 * Lightweight, zero-dependency JSON serializer and tokenizer.
 * Guarantees zero reflection and 100% JVM test compatibility without Android org.json stubs.
 */
object IpcJsonUtils {
    fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '\"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    fun quoteNullable(s: String?): String {
        return if (s == null) "null" else quote(s)
    }

    fun mapToJson(map: Map<String, String>): String {
        return buildString {
            append("{")
            var first = true
            for ((k, v) in map) {
                if (!first) append(",")
                append(quote(k)).append(":").append(quote(v))
                first = false
            }
            append("}")
        }
    }

    fun parseObject(json: String): Map<String, Any?> {
        val trimmed = json.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return emptyMap()
        val content = trimmed.substring(1, trimmed.length - 1).trim()
        if (content.isEmpty()) return emptyMap()

        val result = LinkedHashMap<String, Any?>()
        var i = 0
        val len = content.length

        while (i < len) {
            // Skip whitespace and commas
            while (i < len && (content[i].isWhitespace() || content[i] == ',')) i++
            if (i >= len) break

            // Parse key
            if (content[i] != '\"') break
            val keyStart = i + 1
            i++
            val keyBuilder = StringBuilder()
            while (i < len) {
                val c = content[i]
                if (c == '\\' && i + 1 < len) {
                    keyBuilder.append(content[i + 1])
                    i += 2
                } else if (c == '\"') {
                    i++
                    break
                } else {
                    keyBuilder.append(c)
                    i++
                }
            }
            val key = keyBuilder.toString()

            // Skip to colon
            while (i < len && (content[i].isWhitespace() || content[i] == ':')) i++
            if (i >= len) break

            // Parse value
            val (value, nextIdx) = parseValue(content, i)
            result[key] = value
            i = nextIdx
        }
        return result
    }

    private fun parseValue(str: String, startIdx: Int): Pair<Any?, Int> {
        var i = startIdx
        val len = str.length
        while (i < len && str[i].isWhitespace()) i++
        if (i >= len) return Pair(null, i)

        val firstChar = str[i]
        return when {
            firstChar == '\"' -> {
                i++
                val sb = StringBuilder()
                while (i < len) {
                    val c = str[i]
                    if (c == '\\' && i + 1 < len) {
                        val next = str[i + 1]
                        when (next) {
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            '\"' -> sb.append('\"')
                            '\\' -> sb.append('\\')
                            else -> sb.append(next)
                        }
                        i += 2
                    } else if (c == '\"') {
                        i++
                        break
                    } else {
                        sb.append(c)
                        i++
                    }
                }
                Pair(sb.toString(), i)
            }
            firstChar == '{' -> {
                // Nested object (e.g. Map<String, String>)
                var braceCount = 0
                val objStart = i
                while (i < len) {
                    if (str[i] == '{') braceCount++
                    else if (str[i] == '}') {
                        braceCount--
                        if (braceCount == 0) {
                            i++
                            break
                        }
                    }
                    i++
                }
                val objSub = str.substring(objStart, i)
                val map = parseObject(objSub)
                // convert to Map<String, String> if values are strings
                val stringMap = LinkedHashMap<String, String>()
                for ((k, v) in map) {
                    if (v != null) stringMap[k] = v.toString()
                }
                Pair(stringMap, i)
            }
            str.startsWith("true", i) -> Pair(true, i + 4)
            str.startsWith("false", i) -> Pair(false, i + 5)
            str.startsWith("null", i) -> Pair(null, i + 4)
            else -> {
                // Number
                val numStart = i
                while (i < len && (str[i].isDigit() || str[i] == '-' || str[i] == '.')) i++
                val numStr = str.substring(numStart, i)
                val numVal = numStr.toLongOrNull() ?: numStr.toDoubleOrNull() ?: 0L
                Pair(numVal, i)
            }
        }
    }
}
