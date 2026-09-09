package com.uong.phormi

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Disposable email testing surface backed by a configured self-hosted MailTub server. */
class PhormiDisposableEmailActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var serverInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var ttlSpinner: Spinner
    private lateinit var current: TextView
    private lateinit var inbox: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
        purgeLocalRecords()
    }

    private fun render() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 20, 20, 24); setBackgroundColor(Color.rgb(11, 18, 32)) }
        root.addView(TextView(this).apply { text = "Disposable Email Lab"; textSize = 23f; setTextColor(Color.WHITE); setPadding(0, 0, 0, 8) })
        root.addView(TextView(this).apply { text = "For legitimate testing: create temporary inboxes on your own MailTub server, receive verification mail, copy the address, and delete the mailbox when finished."; setTextColor(Color.LTGRAY); setPadding(0, 0, 0, 14) })
        serverInput = EditText(this).apply { hint = "MailTub server, e.g. https://mail.example.com"; setSingleLine(true); setText(PhormiDisposableEmailStore.server(this@PhormiDisposableEmailActivity)); setTextColor(Color.WHITE) }
        root.addView(serverInput)
        apiKeyInput = EditText(this).apply { hint = "Optional API key"; setSingleLine(true); setText(PhormiDisposableEmailStore.apiKey(this@PhormiDisposableEmailActivity)); setTextColor(Color.WHITE); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        root.addView(apiKeyInput)
        ttlSpinner = Spinner(this)
        ttlSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, arrayOf("1 hour", "6 hours", "24 hours", "7 days"))
        root.addView(ttlSpinner)
        root.addView(Button(this).apply { text = "Save server"; setOnClickListener { saveConfig(); toast("Mail server saved") } })
        root.addView(Button(this).apply { text = "Generate disposable address"; setOnClickListener { generate() } })
        current = TextView(this).apply { text = "No mailbox selected"; textSize = 16f; setTextColor(Color.rgb(56, 189, 248)); setPadding(0, 14, 0, 10) }
        root.addView(current)
        root.addView(Button(this).apply { text = "Copy address"; setOnClickListener { copyCurrent() } })
        root.addView(Button(this).apply { text = "Refresh inbox"; setOnClickListener { refresh() } })
        root.addView(Button(this).apply { text = "Delete current mailbox"; setOnClickListener { deleteCurrent() } })
        root.addView(Button(this).apply { text = "Open MailTub web inbox"; setOnClickListener { openServer() } })
        inbox = TextView(this).apply { text = "Inbox is empty. Generate an address, then refresh after a message arrives."; setTextColor(Color.WHITE); setPadding(0, 12, 0, 12) }
        val scroll = ScrollView(this); scroll.addView(inbox); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        PhormiDisposableEmailStore.list(this).firstOrNull()?.let { current.text = it.address; loadInbox(it) }
    }

    private fun saveConfig() { PhormiDisposableEmailStore.saveConfig(this, serverInput.text.toString(), apiKeyInput.text.toString()) }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun selectedTtlHours() = when (ttlSpinner.selectedItemPosition) { 0 -> 1; 1 -> 6; 2 -> 24; else -> 168 }

    private fun generate() {
        saveConfig(); val server = PhormiDisposableEmailStore.server(this); val apiKey = PhormiDisposableEmailStore.apiKey(this); setBusy("Creating mailbox…")
        executor.execute {
            runCatching {
                val body = JSONObject().put("localPart", PhormiDisposableEmailStore.newLocalPart()).put("ttlHours", selectedTtlHours()).toString()
                val result = request("POST", "$server/api/v1/mailbox", body, apiKey)
                val address = findString(JSONObject(result), "address") ?: findString(JSONObject(result), "email") ?: error("Server did not return a mailbox address")
                val now = System.currentTimeMillis(); val mailbox = PhormiDisposableEmailStore.Mailbox(address, now, now + 30L * 24L * 60L * 60L * 1000L, server)
                PhormiDisposableEmailStore.add(this, mailbox)
                runOnUiThread { current.text = address; inbox.text = "Mailbox created. Waiting for messages…"; toast("Disposable address created"); loadInbox(mailbox) }
            }.onFailure { runOnUiThread { current.text = "Create failed: ${it.message ?: "unknown error"}" } }
        }
    }

    private fun refresh() { val address = current.text.toString().trim(); PhormiDisposableEmailStore.list(this).firstOrNull { it.address == address }?.let { loadInbox(it) } }
    private fun loadInbox(mailbox: PhormiDisposableEmailStore.Mailbox) {
        inbox.text = "Refreshing ${mailbox.address}…"; val apiKey = PhormiDisposableEmailStore.apiKey(this)
        executor.execute { runCatching {
            val result = request("GET", "${mailbox.server}/api/v1/mailbox/${Uri.encode(mailbox.address)}/emails", null, apiKey)
            runOnUiThread { inbox.text = formatInbox(result) }
        }.onFailure { runOnUiThread { inbox.text = "Inbox refresh failed: ${it.message ?: "unknown error"}" } } }
    }

    private fun deleteCurrent() {
        val address = current.text.toString().trim(); val mailbox = PhormiDisposableEmailStore.list(this).firstOrNull { it.address == address } ?: return; inbox.text = "Deleting mailbox…"
        executor.execute { runCatching { request("DELETE", "${mailbox.server}/api/v1/mailbox/${Uri.encode(address)}", null, PhormiDisposableEmailStore.apiKey(this)); PhormiDisposableEmailStore.remove(this, address); runOnUiThread { current.text = "No mailbox selected"; inbox.text = "Mailbox deleted."; toast("Mailbox deleted") } }.onFailure { runOnUiThread { inbox.text = "Delete failed: ${it.message ?: "unknown error"}" } } }
    }

    private fun copyCurrent() { val value = current.text.toString().trim(); if (!value.contains("@")) return; val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager; cm.setPrimaryClip(ClipData.newPlainText("Disposable email", value)); toast("Address copied") }
    private fun openServer() { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PhormiDisposableEmailStore.server(this)))) } }

    private fun request(method: String, url: String, body: String?, apiKey: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply { requestMethod = method; connectTimeout = 10000; readTimeout = 15000; setRequestProperty("Accept", "application/json"); if (apiKey.isNotBlank()) setRequestProperty("X-API-Key", apiKey) }
        if (body != null) { conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json"); conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) } }
        val code = conn.responseCode; val stream = if (code in 200..299) conn.inputStream else conn.errorStream; val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty(); if (code !in 200..299) error("HTTP $code: ${text.take(240)}"); return text
    }

    private fun findString(value: Any?, key: String): String? {
        when (value) {
            is JSONObject -> { if (value.has(key) && !value.isNull(key)) return value.optString(key).takeIf { it.isNotBlank() }; val names = value.names() ?: return null; for (i in 0 until names.length()) findString(value.opt(names.getString(i)), key)?.let { return it } }
            is JSONArray -> for (i in 0 until value.length()) findString(value.opt(i), key)?.let { return it }
        }
        return null
    }

    private fun formatInbox(raw: String): String = runCatching {
        val root = JSONObject(raw); val array = root.optJSONArray("emails") ?: root.optJSONArray("data") ?: JSONArray(); if (array.length() == 0) return@runCatching "No messages yet."
        buildString { for (i in 0 until array.length()) { val o = array.optJSONObject(i) ?: continue; append("From: ").append(o.optString("from", o.optString("sender"))).append('\n'); append("Subject: ").append(o.optString("subject", "(no subject)")).append('\n'); append("Date: ").append(o.optString("date", o.optString("createdAt"))).append("\n\n"); append(o.optString("text", o.optString("body", ""))).append("\n\n────────────\n\n") } }
    }.getOrElse { raw.take(12000) }

    private fun purgeLocalRecords() {
        val now = System.currentTimeMillis(); PhormiDisposableEmailStore.list(this).filter { it.expiresAt <= now }.forEach { mailbox ->
            executor.execute { runCatching { request("DELETE", "${mailbox.server}/api/v1/mailbox/${Uri.encode(mailbox.address)}", null, PhormiDisposableEmailStore.apiKey(this)) }; PhormiDisposableEmailStore.remove(this, mailbox.address) }
        }
    }

    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }
}
