package com.allvideodownloader.avd.core

import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Android port of `webfinder/config.py`.
 *
 * Settings live in `settings.json` inside the app's private files directory
 * (the Android equivalent of `~/.avd`), with the same defaults, the same deep
 * merge on load, and the same "retired keys must never resurrect" rule as the
 * desktop app. The one addition is [homeDir], which the Android layer points at
 * app storage instead of a user home directory.
 */
object AppConfig {
    const val APP_NAME = "All Video Downloader"
    const val APP_VERSION = "2.3.1"

    /** Set once by the Android layer (`AvdApp`) before anything reads settings. */
    var configDir: File = File(System.getProperty("java.io.tmpdir") ?: ".", ".avd")
    /** The default download folder — the Android layer supplies a writable path. */
    var defaultDownloadDir: String = File(configDir, "downloads").absolutePath

    val configFile: File get() = File(configDir, "settings.json")

    fun defaults(): MutableMap<String, Any?> = linkedMapOf(
        // --- downloads -----------------------------------------------------
        "download_dir" to defaultDownloadDir,
        "max_concurrent" to 3L,
        "chunk_size_kb" to 256L,
        "simulate_on_network_error" to true,
        "proxy" to "",
        // --- video / capture -----------------------------------------------
        "default_container" to "mp4",
        "default_quality" to "1080",
        "ffmpeg_path" to "",
        "ytdlp_browser_cookies" to "",
        "cookies_file" to "",
        // --- appearance ----------------------------------------------------
        "accent" to "violet",
        "ambient_effects" to true,
    )

}

/** Keys of the retired search module: a stale file must not resurrect them. */
private val DEAD_KEYS = listOf(
    "engines", "result_limit", "safe_search", "resolve_pages", "demo_mode",
    "vertical_mode", "file_types",
    "instagram_include", "instagram_limit", "instagram_delay",
)

class Settings {

    private val lock = ReentrantLock()
    private var data: MutableMap<String, Any?> = AppConfig.defaults()

    init {
        load()
    }

    // -- persistence ------------------------------------------------------
    fun load() {
        var raw = try {
            if (AppConfig.configFile.isFile) Json.parseOrNull(AppConfig.configFile.readText()).asMap() else null
        } catch (_: Exception) {
            null
        }
        if (raw == null) raw = migrateLegacy()
        if (raw != null && raw.isNotEmpty()) {
            val merged = deepMerge(AppConfig.defaults(), raw)
            for (dead in DEAD_KEYS) merged.remove(dead)
            lock.withLock { data = merged }
        }
        runCatching { File(data["download_dir"].str()).mkdirs() }
    }

    /** One-shot migration from the retired `~/.webfinder` folder, if present. */
    private fun migrateLegacy(): MutableMap<String, Any?>? {
        return try {
            val legacy = File(System.getProperty("user.home") ?: ".", ".webfinder/settings.json")
            if (!legacy.isFile) null
            else {
                AppConfig.configDir.mkdirs()
                legacy.copyTo(AppConfig.configFile, overwrite = true)
                Json.parseOrNull(AppConfig.configFile.readText()).asMap()
            }
        } catch (_: Exception) {
            null
        }
    }

    fun save() {
        runCatching {
            AppConfig.configDir.mkdirs()
            val tmp = File(AppConfig.configFile.parentFile, "settings.tmp")
            tmp.writeText(Json.write(lock.withLock { LinkedHashMap(data) }))
            if (AppConfig.configFile.exists()) AppConfig.configFile.delete()
            if (!tmp.renameTo(AppConfig.configFile)) tmp.copyTo(AppConfig.configFile, overwrite = true)
        }
    }

    // -- access -----------------------------------------------------------
    fun snapshot(): MutableMap<String, Any?> = lock.withLock { Json.parse(Json.write(data)).asMap() ?: LinkedHashMap() }

    fun update(patch: Map<String, Any?>): MutableMap<String, Any?> {
        val merged = lock.withLock {
            data = deepMerge(data, patch)
            LinkedHashMap(data)
        }
        runCatching { File(merged["download_dir"].str()).mkdirs() }
        save()
        return merged
    }

    fun get(key: String, fallback: Any? = null): Any? = lock.withLock { data[key] ?: fallback }

    fun getString(key: String, fallback: String = ""): String =
        (lock.withLock { data[key] } as? String)?.takeIf { it.isNotEmpty() } ?: fallback

    fun getInt(key: String, fallback: Int = 0): Int =
        (lock.withLock { data[key] }?.num() ?: fallback.toDouble()).toInt()

    fun getBool(key: String, fallback: Boolean = false): Boolean =
        (lock.withLock { data[key] } as? Boolean) ?: fallback

    private fun deepMerge(base: Map<String, Any?>, patch: Map<String, Any?>): MutableMap<String, Any?> {
        val out = LinkedHashMap<String, Any?>(base)
        for ((k, v) in patch) {
            val old = out[k]
            val patchMap = v.asMap()
            if (patchMap != null && old.asMap() != null) {
                out[k] = deepMerge(old.asMap()!!, patchMap)
            } else {
                out[k] = v
            }
        }
        return out
    }
}

/** The process-wide settings object (mirrors `config.settings`). */
object Config {
    @Volatile
    private var instance: Settings? = null

    fun settings(): Settings =
        instance ?: synchronized(this) { instance ?: Settings().also { instance = it } }

    fun reload() {
        synchronized(this) {
            instance = Settings()
        }
    }
}
