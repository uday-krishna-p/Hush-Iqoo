package com.hush.log

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.hush.HLog
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Commander's record of the session: one JSON line per message, exactly as it crossed the network,
 * plus header / hush / ranking lines. Export writes it to Downloads/ so the laptop can show it.
 */
class SessionLog {

    private val lines = ArrayList<String>()
    private val startedAt = Date()
    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    @Synchronized fun add(line: String) {
        lines.add(line)
    }

    @Synchronized fun addRecord(type: String, fields: Map<String, Any?>) {
        val o = JSONObject()
        o.put("type", type)
        o.put("at", System.currentTimeMillis())
        for ((k, v) in fields) if (v != null) o.put(k, v)
        lines.add(o.toString())
    }

    @Synchronized fun size() = lines.size

    /** Writes Downloads/hush-<date>-<time>.jsonl and returns the file name, or null on failure. */
    @Synchronized fun export(context: Context, header: Map<String, Any?>): String? {
        val name = "hush-${stamp.format(startedAt)}.jsonl"
        return try {
            val head = JSONObject().apply {
                put("type", "header")
                put("exportedAt", System.currentTimeMillis())
                for ((k, v) in header) if (v != null) put(k, v)
            }.toString()
            val body = (listOf(head) + lines).joinToString("\n") + "\n"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("insert returned null")
            resolver.openOutputStream(uri)?.use { it.write(body.toByteArray(Charsets.UTF_8)) } ?: throw IllegalStateException("no output stream")
            HLog.d("Exported ${lines.size + 1} lines to Downloads/$name")
            name
        } catch (e: Exception) {
            HLog.d("Export FAILED: $e")
            null
        }
    }
}
