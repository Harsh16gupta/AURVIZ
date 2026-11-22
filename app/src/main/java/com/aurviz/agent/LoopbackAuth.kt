package com.aurviz.agent

// Session 2 thin wiring: the loopback return address for Google sign-in.
// Google sends the browser to http://127.0.0.1:<port>/callback on this
// phone; we read the ?code= off that request. Device-verified, no unit test
// (sockets need a real phone, same rule as the WebView bridge).

class LoopbackReceiver {
    private val server =
        java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))

    val redirectUri: String get() = "http://127.0.0.1:${server.localPort}/callback"

    // Blocks until Google's redirect arrives (or timeout). Returns the code.
    // 10 minutes: consent screens are worth reading slowly.
    // Loops past dead probes (our own self-test, Chrome's speculative
    // connections): only a request line carrying ?code= counts.
    fun waitForCode(timeoutMs: Int = 600_000): String? {
        android.util.Log.d("AURVIZ", "loopback: listening on $redirectUri")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) {
                android.util.Log.d("AURVIZ", "loopback: timed out waiting")
                closeQuietly()
                return null
            }
            server.soTimeout = remaining.coerceAtMost(600_000).toInt()
            val socket =
                try {
                    server.accept()
                } catch (e: java.net.SocketTimeoutException) {
                    android.util.Log.d("AURVIZ", "loopback: timed out waiting")
                    closeQuietly()
                    return null
                }
            socket.soTimeout = 15_000
            try {
                val reader = socket.getInputStream().bufferedReader()
                val requestLine = reader.readLine()
                if (requestLine == null) {
                    android.util.Log.d("AURVIZ", "loopback: dead probe, still waiting")
                    try {
                        socket.close()
                    } catch (_: Exception) {
                    }
                    continue
                }
                while (true) {
                    val header = reader.readLine() ?: break
                    if (header.isEmpty()) break
                }
                val code = Regex("[?&]code=([^& ]+)").find(requestLine)?.groupValues?.get(1)
                val body = "<html><body>Signed in — return to AURVIZ.</body></html>"
                val head =
                    "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n" +
                        "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n"
                val out = socket.getOutputStream()
                out.write(head.toByteArray())
                out.write(body.toByteArray())
                out.flush()
                try {
                    socket.close()
                } catch (_: Exception) {
                }
                if (code == null) {
                    android.util.Log.d("AURVIZ", "loopback: request without code, still waiting")
                    continue
                }
                android.util.Log.d("AURVIZ", "loopback: got code")
                closeQuietly()
                return java.net.URLDecoder.decode(code, "UTF-8")
            } catch (_: Exception) {
                try {
                    socket.close()
                } catch (_: Exception) {
                }
                continue
            }
        }
    }

    private fun closeQuietly() {
        try {
            server.close()
        } catch (_: Exception) {
        }
    }
}
