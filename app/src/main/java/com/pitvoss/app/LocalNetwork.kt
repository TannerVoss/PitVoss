package com.pitvoss.app

/** Private IPv4 destinations only; never resolve user-supplied host names. */
object LocalNetwork {
    fun isAllowedHost(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        val bytes = parts.map { part ->
            val value = part.toIntOrNull() ?: return false
            if (value !in 0..255 || value.toString() != part) return false
            value
        }
        return bytes[0] == 10 || (bytes[0] == 172 && bytes[1] in 16..31) ||
            (bytes[0] == 192 && bytes[1] == 168) || (bytes[0] == 169 && bytes[1] == 254)
    }
    fun readBounded(stream: java.io.InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            require(out.size() + count <= 262144) { "Grill response exceeded the supported size" }
            out.write(buffer, 0, count)
        }
        return out.toString("UTF-8")
    }
    fun requireAllowed(host: String) {
        require(isAllowedHost(host)) { "Enter the grill's private local-network address" }
    }
}
