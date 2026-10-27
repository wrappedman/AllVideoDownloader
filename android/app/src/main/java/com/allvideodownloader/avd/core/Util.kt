package com.allvideodownloader.avd.core

/**
 * Small helpers that avoid Java-8-only APIs.
 *
 * `java.util.Base64` only exists from Android 8 (API 26); this build targets
 * API 21 and — because it is compiled with dx instead of d8 — cannot rely on
 * API desugaring. These 30 lines keep the minimum Android version as low as the
 * desktop app's feature set allows.
 */
object Util {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun base64Encode(data: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xff
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xff else -1
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xff else -1
            sb.append(ALPHABET[b0 shr 2])
            sb.append(ALPHABET[((b0 and 0x03) shl 4) or (if (b1 >= 0) b1 shr 4 else 0)])
            sb.append(if (b1 >= 0) ALPHABET[((b1 and 0x0f) shl 2) or (if (b2 >= 0) b2 shr 6 else 0)] else '=')
            sb.append(if (b2 >= 0) ALPHABET[b2 and 0x3f] else '=')
            i += 3
        }
        return sb.toString()
    }

    /** Lenient decode (ignores whitespace/URL-safe chars), like base64's validate=False. */
    fun base64Decode(text: String): ByteArray {
        val clean = text.filter { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' || it == '-' || it == '_' }
            .replace('-', '+').replace('_', '/')
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in clean) {
            if (c == '=') break
            val v = ALPHABET.indexOf(c)
            if (v < 0) continue
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xff)
            }
        }
        return out.toByteArray()
    }
}
