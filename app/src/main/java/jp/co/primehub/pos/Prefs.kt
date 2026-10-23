package jp.co.primehub.pos

import android.content.Context

/** which store this tablet prints for (set from Admin → Printers → "Use this tablet") */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("print", Context.MODE_PRIVATE)
    fun url(c: Context) = sp(c).getString("url", "") ?: ""
    fun key(c: Context) = sp(c).getString("key", "") ?: ""
    fun store(c: Context) = sp(c).getString("store", "") ?: ""
    fun kanji(c: Context) = sp(c).getBoolean("kanji", true)
    fun configured(c: Context) = url(c).isNotEmpty() && key(c).isNotEmpty()
    fun save(c: Context, url: String, key: String, store: String) =
        sp(c).edit().putString("url", url).putString("key", key).putString("store", store).apply()
    fun clear(c: Context) = sp(c).edit().remove("url").remove("key").remove("store").apply()
}
