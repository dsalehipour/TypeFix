package com.typefix.keyboard.ime

import android.content.Context
import org.json.JSONArray

/**
 * A small persistent clipboard history (up to [MAX] recent text copies). Android
 * itself only exposes the single current clip, so the IME records clips into this
 * list whenever it can read them (while the keyboard is shown / on copy events).
 */
object ClipboardHistory {

    private const val MAX = 100
    private const val PREFS = "typefix_clipboard"
    private const val KEY = "items"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun items(context: Context): List<String> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { arr.getString(it) }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, list: List<String>) {
        val arr = JSONArray()
        list.take(MAX).forEach { arr.put(it) }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    /** Adds [text] to the front (deduped, most-recent-first). No-op if blank. */
    fun add(context: Context, text: String?) {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return
        val current = items(context)
        if (current.firstOrNull() == t) return
        save(context, listOf(t) + current.filterNot { it == t })
    }

    fun remove(context: Context, texts: Set<String>) {
        if (texts.isEmpty()) return
        save(context, items(context).filterNot { it in texts })
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).apply()
    }
}
