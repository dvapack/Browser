package com.flowersinvase.browser

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import org.json.JSONArray
import org.json.JSONObject
import java.net.IDN
import java.net.URI

internal class BrowserTab(val id: Int, url: String? = null, title: String = "") {
    var url by mutableStateOf(url)
    var title by mutableStateOf(title)
    var progress by mutableIntStateOf(100)
    var failed by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    var canGoForward by mutableStateOf(false)
    var rendererGone by mutableStateOf(false)
    var history: Bundle? = null
    var view: WebView? = null
}

internal class BrowserSession(application: Application) : AndroidViewModel(application) {
    private val storage = application.getSharedPreferences("browser_session", Context.MODE_PRIVATE)
    private val recentSites = mutableStateListOf<String>().apply {
        val saved = runCatching { JSONArray(storage.getString("sites", "[]")) }.getOrNull()
        for (index in 0 until minOf(saved?.length() ?: 0, 64)) {
            val site = saved?.optString(index).orEmpty()
            if (isWebUrl(site)) add(site)
        }
    }
    private val openTabs = mutableStateListOf<BrowserTab>()
    val tabs: List<BrowserTab> get() = openTabs
    private var nextId = 0

    var active by mutableStateOf(restore())
        private set

    fun select(tab: BrowserTab) {
        active = tab
    }

    fun add() {
        active = BrowserTab(nextId++).also(openTabs::add)
    }

    fun close(tab: BrowserTab) {
        val index = openTabs.indexOf(tab)
        if (index < 0) return
        openTabs.removeAt(index)
        tab.history = null
        if (openTabs.isEmpty()) add()
        else if (active == tab) active = openTabs[index.coerceAtMost(openTabs.lastIndex)]
    }

    fun navigate(url: String) {
        val tab = active
        tab.failed = false
        tab.rendererGone = false
        tab.canGoForward = false
        tab.progress = 0
        if (tab.url != url) tab.title = ""
        tab.url = url
        tab.view?.loadUrl(url)
    }

    fun reload() {
        val tab = active
        val url = tab.url ?: return
        tab.failed = false
        tab.rendererGone = false
        tab.progress = 0
        tab.view?.let { view ->
            if (view.url == url) view.reload() else view.loadUrl(url)
        }
    }

    fun forward() {
        val tab = active
        val view = tab.view ?: return
        if (view.canGoForward()) {
            tab.failed = false
            view.goForward()
        }
    }

    fun back() {
        val tab = active
        val view = tab.view
        if (view?.canGoBack() == true) {
            tab.failed = false
            view.goBack()
        } else {
            tab.url = null
            tab.title = ""
            tab.failed = false
            tab.rendererGone = false
            tab.canGoForward = false
            tab.history = null
        }
    }

    fun recordSite(url: String) {
        if (!isWebUrl(url)) return
        val uri = Uri.parse(url)
        val site = uri.buildUpon()
            .encodedAuthority(uri.encodedAuthority.orEmpty().substringAfterLast('@'))
            .clearQuery().fragment(null).path(null).build().toString()
        if (recentSites.firstOrNull() == site) return
        recentSites.remove(site)
        recentSites.add(0, site)
        if (recentSites.size > 64) recentSites.removeAt(recentSites.lastIndex)
        storage.edit().putString("sites", JSONArray(recentSites).toString()).apply()
    }

    fun suggestions(input: String): List<String> {
        val query = suggestionKey(input)
        if (query.isBlank() || query.any(Char::isWhitespace)) return emptyList()
        val matching = (recentSites.asSequence() + suggestedSites.asSequence())
            .filter { suggestionKey(it).startsWith(query, ignoreCase = true) }
            .distinctBy { suggestionKey(it).lowercase() }
            .take(4)
            .toList()
        return matching.ifEmpty { listOfNotNull(resolveWebAddress(input.trim())) }
    }

    fun snapshot(): Pair<String, Int> {
        val data = JSONArray()
        tabs.forEach { tab ->
            data.put(JSONObject().put("url", tab.url).put("title", tab.title))
        }
        return data.toString() to tabs.indexOf(active)
    }

    fun save(snapshot: Pair<String, Int> = snapshot()) {
        val (data, index) = snapshot
        storage.edit().putString("tabs", data).putInt("active", index).apply()
    }

    private fun restore(): BrowserTab {
        val data = runCatching { JSONArray(storage.getString("tabs", "[]")) }.getOrNull()
        for (index in 0 until (data?.length() ?: 0)) {
            val tab = data?.optJSONObject(index) ?: continue
            val url = tab.optString("url").takeIf(::isWebUrl)
            openTabs.add(BrowserTab(nextId++, url, tab.optString("title")))
        }
        if (openTabs.isEmpty()) openTabs.add(BrowserTab(nextId++))
        return openTabs[storage.getInt("active", 0).coerceIn(openTabs.indices)]
    }
}

internal fun isWebUrl(value: String): Boolean {
    val uri = Uri.parse(value)
    return (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
        !uri.host.isNullOrBlank()
}

private val webScheme = Regex("^https?://", RegexOption.IGNORE_CASE)

internal fun resolveAddress(input: String): String? {
    val value = input.trim()
    if (value.isEmpty()) return null

    return resolveWebAddress(value) ?: Uri.parse("https://www.google.com/search").buildUpon()
        .appendQueryParameter("q", value).build().toString()
}

private val suggestedSites = listOf(
    "https://www.google.com", "https://www.youtube.com", "https://github.com",
    "https://www.wikipedia.org", "https://yandex.ru", "https://vk.com"
)

private fun suggestionKey(address: String): String = address.trim().lowercase()
    .replaceFirst(webScheme, "").removePrefix("www.").trimEnd('/')

private fun resolveWebAddress(value: String): String? {
    val explicit = webScheme.containsMatchIn(value)
    if (explicit || value.none(Char::isWhitespace)) {
        val candidate = if (explicit) value else "https://$value"
        val uri = Uri.parse(candidate.replace(" ", "%20"))
        val host = uri.host.orEmpty()
        val asciiHost = runCatching {
            if (host.startsWith("[") && host.endsWith("]")) host else IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES)
        }.getOrNull()
        val looksLikeHost = explicit || host.equals("localhost", true) ||
            '.' in host || host.startsWith("[")
        val rawHost = Uri.decode(uri.encodedAuthority.orEmpty().substringAfterLast('@'))
        val portSuffix = rawHost.removePrefix(host)
        if (looksLikeHost && !asciiHost.isNullOrEmpty() &&
            (portSuffix.isEmpty() || uri.port in 0..65535)
        ) {
            val address = uri.buildUpon()
                .scheme(uri.scheme?.lowercase())
                .encodedAuthority(buildString {
                    uri.encodedUserInfo?.let { append(it).append('@') }
                    append(asciiHost)
                    if (uri.port != -1) append(':').append(uri.port)
                })
                .build().toString()
            val valid = runCatching {
                val normalized = URI(address)
                normalized.host != null && normalized.port in -1..65535
            }.getOrDefault(false)
            if (valid && (explicit || uri.userInfo == null)) return address
        }
    }
    return null
}
