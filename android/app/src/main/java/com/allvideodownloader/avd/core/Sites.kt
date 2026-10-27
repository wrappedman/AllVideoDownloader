package com.allvideodownloader.avd.core

import java.util.Locale

/**
 * Android port of `webfinder/core/sites.py` (logic half).
 *
 * A *recipe* is data, not code: hostnames plus the player family a site uses.
 * The heavy lifting is generic ([Embed]), so adding a site is one line in the
 * Python table — which the generator turns into [SiteRegistry] here.
 */
object Sites {

    val RECIPES: List<Recipe> = SiteRegistry.RECIPES
    val FAMILIES: Map<String, List<FamilyPattern>> = SiteRegistry.FAMILIES
    val EMBED_HOSTS: List<EmbedHost> = SiteRegistry.EMBED_HOSTS

    private val BY_DOMAIN: Map<String, Recipe> = buildMap {
        for (r in RECIPES) for (d in r.domains) putIfAbsent(d.lowercase(Locale.ROOT), r)
    }

    fun hostOf(url: String): String {
        val host = try {
            (UrlKit.parse(url).netloc).lowercase(Locale.ROOT).substringAfterLast('@').substringBefore(':')
        } catch (_: Exception) {
            return ""
        }
        var h = host.trimEnd('.')
        if (h.startsWith("www.")) h = h.substring(4)
        return h
    }

    /** Recipe matching this URL's host (longest suffix wins), else null. */
    fun recipeFor(url: String): Recipe? {
        val host = hostOf(url)
        if (host.isEmpty()) return null
        BY_DOMAIN[host]?.let { return it }
        val parts = host.split(".")
        for (i in 1 until parts.size - 1) {
            val cand = parts.subList(i, parts.size).joinToString(".")
            BY_DOMAIN[cand]?.let { return it }
        }
        return null
    }

    /** Friendly site label: recipe name, else a title-cased host (IPs → ""). */
    fun siteName(url: String): String {
        recipeFor(url)?.let { return it.name }
        val host = hostOf(url)
        if (host.isEmpty() || !host.contains(".") || host.replace(".", "").all { it.isDigit() }) return ""
        var parts = host.split(".")
        if (parts.size > 2 && parts[0] in listOf("www", "m", "mobile", "amp")) parts = parts.drop(1)
        return parts[0].replace('-', ' ').replace('_', ' ')
            .split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }

    /** Player label when this URL is a known cross-origin embed host, else "". */
    fun embedHostFor(url: String?): String {
        val low = (url ?: "").lowercase(Locale.ROOT)
        if (low.isEmpty()) return ""
        for (h in EMBED_HOSTS) if (h.regex.matcher(low).find()) return h.label
        return ""
    }

    /** Is this a player URL worth hopping into (known host or embed-shaped)? */
    fun isEmbedUrl(url: String?): Boolean {
        if (embedHostFor(url).isNotEmpty()) return true
        val low = (url ?: "").lowercase(Locale.ROOT)
        return listOf("video", "embed", "player", "watch", "media", "/vid", "/e/").any { it in low }
    }

    /** Which player/config style does this page use? (for the UI badge) */
    fun detectFamily(html: String?): String {
        val text = html ?: ""
        for ((name, pat) in SiteRegistry.FAMILY_MARKERS) if (pat.matcher(text).find()) return name
        return ""
    }

    /** Every media candidate this page exposes: recipe family + generic extraction. */
    fun candidates(html: String?, url: String, limit: Int = 48): List<MutableMap<String, Any?>> {
        val recipe = recipeFor(url)
        val out = LinkedHashMap<String, MutableMap<String, Any?>>()

        fun add(item: MutableMap<String, Any?>?) {
            if (item == null) return
            val key = item["url"].str()
            val old = out[key]
            if (old == null) {
                out[key] = item
                return
            }
            if (item["score"].int() > old["score"].int()) {
                // a named player slot outranks a random URL, but it must not forget
                // the resolution another pass already learned for the same file
                item["height"] = if (item["height"].int() != 0) item["height"] else old["height"] ?: 0
                item["quality"] = (item["quality"].str()).ifEmpty { old["quality"].str() }
                out[key] = item
            }
        }

        // 1) generic scan of the (de-escaped) document — covers every player style
        for (c in Embed.extract(html, url, limit * 2, if (recipe != null) "recipe" else "embed")) add(c)

        // 2) family patterns (they carry the human label: High/Low/HD/HLS).
        //    An UNKNOWN host still gets a family when the page announces its
        //    player — that is why an unlisted tube site works without a recipe.
        val norm = Embed.normalize(html)
        val licenseCode = Embed.kvsLicenseCode(norm)
        var famKey = recipe?.family ?: ""
        if (famKey.isEmpty() || famKey == "generic") {
            val detected = detectFamily(html)
            if (detected in FAMILIES) famKey = detected
        }
        val fam = FAMILIES[famKey.ifEmpty { "generic" }] ?: emptyList()
        for (rule in fam) {
            val m = rule.regex.matcher(norm)
            while (m.find()) {
                val c = Embed.candidate(m.group(1), url, "recipe", rule.label, licenseCode) ?: continue
                c["score"] = c["score"].int() + 6 // a named player slot beats a random URL
                add(c)
            }
        }

        val items = out.values.sortedByDescending { it["score"].int() }.take(limit)
        val name = recipe?.name ?: ""
        for (c in items) {
            c["site"] = name
            c["recipe"] = recipe?.key ?: ""
        }
        return items
    }

    /** Public list for the UI/API: what the app knows about out of the box. */
    fun catalog(): List<MutableMap<String, Any?>> = RECIPES.map { r ->
        linkedMapOf(
            "key" to r.key, "name" to r.name, "domains" to r.domains,
            "family" to r.family, "note" to r.note, "adapter" to r.adapter,
        )
    }
}
