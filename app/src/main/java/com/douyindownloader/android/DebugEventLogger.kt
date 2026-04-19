package com.douyindownloader.android

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugEventLogger {
    private const val FILE_NAME = "debug_events.jsonl"
    private const val TAG = "DouyinDebug"
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun reset(context: Context) {
        runCatching { logFile(context).writeText("") }
    }

    @Synchronized
    fun log(context: Context, source: String, event: String, details: Map<String, Any?> = emptyMap()) {
        val payload = JSONObject().apply {
            put("time", timestampFormat.format(Date()))
            put("source", source)
            put("event", event)
            put("details", toJson(details))
        }
        val line = payload.toString()
        Log.d(TAG, line)
        runCatching {
            logFile(context).appendText(line + "\n")
        }
    }

    private fun logFile(context: Context): File = File(context.filesDir, FILE_NAME)

    private fun toJson(map: Map<String, Any?>): JSONObject {
        return JSONObject().apply {
            map.forEach { (key, value) -> put(key, toJsonValue(value)) }
        }
    }

    private fun toJsonValue(value: Any?): Any {
        return when (value) {
            null -> JSONObject.NULL
            is Number, is Boolean, is String -> value
            is Map<*, *> -> JSONObject().apply {
                value.forEach { (key, nestedValue) ->
                    if (key != null) {
                        put(key.toString(), toJsonValue(nestedValue))
                    }
                }
            }
            is Iterable<*> -> JSONArray().apply { value.forEach { put(toJsonValue(it)) } }
            is Array<*> -> JSONArray().apply { value.forEach { put(toJsonValue(it)) } }
            else -> value.toString()
        }
    }
}
