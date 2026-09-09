package com.uong.phormi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Stores temporary mailbox records locally; delivery is provided by a configured mail service. */
object PhormiDisposableEmailStore {
    private const val PREFS = "phormi_disposable_email"
    private const val KEY_SERVER = "server"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_MAILBOXES = "mailboxes"

    data class Mailbox(val address: String, val createdAt: Long, val expiresAt: Long, val server: String)

    fun server(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SERVER, "http://127.0.0.1:8080") ?: "http://127.0.0.1:8080"
    fun apiKey(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_API_KEY, "") ?: ""

    fun saveConfig(context: Context, server: String, apiKey: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SERVER, server.trim().trimEnd('/'))
            .putString(KEY_API_KEY, apiKey.trim()).apply()
    }

    fun add(context: Context, mailbox: Mailbox) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val list = list(context).filter { it.address != mailbox.address }.toMutableList()
        list.add(0, mailbox)
        val json = JSONArray().apply { list.forEach { put(JSONObject().apply {
            put("address", it.address); put("createdAt", it.createdAt); put("expiresAt", it.expiresAt); put("server", it.server)
        }) } }
        prefs.edit().putString(KEY_MAILBOXES, json.toString()).apply()
    }

    fun list(context: Context): List<Mailbox> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MAILBOXES, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw); buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(Mailbox(o.optString("address"), o.optLong("createdAt"), o.optLong("expiresAt"), o.optString("server")))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun remove(context: Context, address: String) {
        val remaining = list(context).filterNot { it.address == address }
        val json = JSONArray().apply { remaining.forEach { put(JSONObject().apply {
            put("address", it.address); put("createdAt", it.createdAt); put("expiresAt", it.expiresAt); put("server", it.server)
        }) } }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MAILBOXES, json.toString()).apply()
    }

    fun newLocalPart(): String = "phormi-${UUID.randomUUID().toString().replace("-", "").take(12)}"
}
