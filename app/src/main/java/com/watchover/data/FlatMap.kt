package com.watchover.data

/**
 * Minimal key/value text codec used to persist settings and runtime state as a
 * single string in DataStore. One atomic value means a partial write can never
 * leave half a configuration behind.
 *
 * Format: `key=value` pairs separated by newlines; backslashes, `=`, and
 * newlines inside values are escaped.
 */
object FlatMap {

    private const val PAIR_SEP = '\n'
    private const val KV_SEP = '='

    fun encode(map: Map<String, String>): String = map.entries.joinToString(PAIR_SEP.toString()) { (k, v) ->
        "${escape(k)}$KV_SEP${escape(v)}"
    }

    fun decode(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (line in raw.split(PAIR_SEP)) {
            if (line.isEmpty()) continue
            val idx = line.indexOf(KV_SEP)
            if (idx <= 0) continue
            val key = unescape(line.substring(0, idx))
            val value = unescape(line.substring(idx + 1))
            result[key] = value
        }
        return result
    }

    private fun escape(value: String): String {
        if (value.none { it == '\\' || it == '\n' || it == '\r' || it == KV_SEP }) return value
        val out = StringBuilder(value.length + 8)
        for (c in value) {
            when (c) {
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                KV_SEP -> out.append("\\=")
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    private fun unescape(value: String): String {
        if (!value.contains('\\')) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (val next = value[i + 1]) {
                    '\\' -> out.append('\\')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    KV_SEP -> out.append(KV_SEP)
                    else -> out.append(next)
                }
                i += 2
            } else {
                out.append(c)
                i += 1
            }
        }
        return out.toString()
    }
}
