package gg.celeste.manager.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

class CelesteVpnService : VpnService() {

    companion object {
        const val TAG = "CelesteVPN"
        const val ACTION_START = "gg.celeste.manager.vpn.START"
        const val ACTION_STOP = "gg.celeste.manager.vpn.STOP"
        private const val CHANNEL_ID = "celeste_vpn"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var isRunning = false
            private set

        private val DNS_REWRITES = mapOf(
            "discord.com" to "alpha.celeste.gg",
            "www.discord.com" to "alpha.celeste.gg",
            "ptb.discord.com" to "alpha.celeste.gg",
            "canary.discord.com" to "alpha.celeste.gg",
            "gateway.discord.gg" to "alpha-gateway.celeste.gg",
            "cdn.discordapp.com" to "cdn.celeste.gg",
            "cdn.discord.com" to "cdn.celeste.gg",
            "media.discordapp.net" to "media.celeste.gg",
            "discordapp.com" to "alpha.celeste.gg",
            "status.discord.com" to "alpha.celeste.gg",
        )

        fun resolveTarget(hostname: String): String? {
            val lower = hostname.lowercase()
            DNS_REWRITES[lower]?.let { return it }
            for ((from, to) in DNS_REWRITES) {
                if (lower.endsWith(".$from")) return to
            }
            return null
        }
    }

    private var vpnFd: ParcelFileDescriptor? = null
    private var workerThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stop()
                return START_NOT_STICKY
            }
            else -> {
                start()
                return START_STICKY
            }
        }
    }

    private fun start() {
        if (isRunning) return

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        val builder = Builder()
            .setSession("Celeste")
            .addAddress("10.215.173.1", 32)
            .addRoute("10.215.173.2", 32)
            .addDnsServer("10.215.173.2")
            .setBlocking(true)
            .setMtu(1500)

        // Only capture Discord's traffic
        try { builder.addAllowedApplication("com.discord") } catch (_: Exception) {}
        try { builder.addAllowedApplication("gg.celeste.app") } catch (_: Exception) {}

        vpnFd = builder.establish()
        if (vpnFd == null) {
            Log.e(TAG, "Failed to establish VPN interface")
            return
        }

        isRunning = true
        Log.i(TAG, "VPN started")

        workerThread = Thread({ dnsLoop() }, "CelesteVPN").apply {
            isDaemon = true
            start()
        }
    }

    private fun stop() {
        isRunning = false
        workerThread?.interrupt()
        workerThread = null
        vpnFd?.close()
        vpnFd = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "VPN stopped")
    }

    override fun onDestroy() { stop(); super.onDestroy() }
    override fun onRevoke() { stop(); super.onRevoke() }

    private fun dnsLoop() {
        val fd = vpnFd?.fileDescriptor ?: return
        val input = FileInputStream(fd)
        val output = FileOutputStream(fd)
        val buf = ByteArray(1500)

        try {
            while (isRunning && !Thread.currentThread().isInterrupted) {
                val n = input.read(buf)
                if (n <= 0) { Thread.sleep(1); continue }

                // Only handle IPv4
                if ((buf[0].toInt() shr 4 and 0xF) != 4) continue

                val proto = buf[9].toInt() and 0xFF
                val ipHL = (buf[0].toInt() and 0xF) * 4

                // Only handle UDP port 53 (DNS)
                if (proto != 17 || n < ipHL + 8) continue
                val dstPort = (buf[ipHL + 2].toInt() and 0xFF shl 8) or (buf[ipHL + 3].toInt() and 0xFF)
                if (dstPort != 53) continue

                val dnsOff = ipHL + 8
                val dnsLen = n - dnsOff
                if (dnsLen < 12) continue

                val dnsData = buf.copyOfRange(dnsOff, n)
                val queryName = parseName(dnsData) ?: continue
                val target = resolveTarget(queryName)

                val responseData: ByteArray
                if (target != null) {
                    Log.i(TAG, "DNS: $queryName -> $target")
                    val addr = resolveExternal(target) ?: continue
                    responseData = forgeResponse(dnsData, addr)
                } else {
                    responseData = forwardToReal(dnsData) ?: continue
                }

                // Build IP+UDP response packet
                val resp = buildResponsePacket(buf, ipHL, responseData)
                output.write(resp)
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Log.e(TAG, "DNS loop error: ${e.message}")
        }
    }

    private fun parseName(dns: ByteArray): String? {
        if (dns.size < 12) return null
        val sb = StringBuilder()
        var pos = 12
        while (pos < dns.size) {
            val len = dns[pos].toInt() and 0xFF
            if (len == 0) break
            if (pos + 1 + len > dns.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(dns, pos + 1, len, Charsets.US_ASCII))
            pos += 1 + len
        }
        return sb.toString().lowercase()
    }

    private fun resolveExternal(host: String): ByteArray? {
        return try {
            val sock = DatagramSocket()
            protect(sock)
            val addr = InetAddress.getByName(host)
            sock.close()
            addr.address
        } catch (e: Exception) {
            Log.e(TAG, "Resolve $host failed: ${e.message}")
            null
        }
    }

    private fun forwardToReal(dnsQuery: ByteArray): ByteArray? {
        return try {
            val sock = DatagramSocket()
            protect(sock)
            sock.soTimeout = 3000
            val server = InetAddress.getByName("8.8.8.8")
            sock.send(DatagramPacket(dnsQuery, dnsQuery.size, server, 53))
            val resp = ByteArray(512)
            val pkt = DatagramPacket(resp, resp.size)
            sock.receive(pkt)
            sock.close()
            resp.copyOf(pkt.length)
        } catch (e: Exception) {
            Log.e(TAG, "DNS forward failed: ${e.message}")
            null
        }
    }

    private fun forgeResponse(query: ByteArray, ip: ByteArray): ByteArray {
        // Find question end
        var pos = 12
        while (pos < query.size && query[pos].toInt() != 0) {
            pos += 1 + (query[pos].toInt() and 0xFF)
        }
        pos += 5 // null + qtype(2) + qclass(2)

        val bb = ByteBuffer.allocate(pos + 16)
        // Transaction ID
        bb.put(query[0]); bb.put(query[1])
        // Flags: standard response, no error
        bb.putShort(0x8180.toShort())
        // QDCOUNT=1, ANCOUNT=1, NSCOUNT=0, ARCOUNT=0
        bb.putShort(1); bb.putShort(1); bb.putShort(0); bb.putShort(0)
        // Question section (copy from query)
        bb.put(query, 12, pos - 12)
        // Answer: name pointer, type A, class IN, TTL 30, rdlen 4, IP
        bb.putShort(0xC00C.toShort())
        bb.putShort(1) // A
        bb.putShort(1) // IN
        bb.putInt(30)   // TTL
        bb.putShort(ip.size.toShort())
        bb.put(ip)

        return bb.array().copyOf(bb.position())
    }

    private fun buildResponsePacket(origIp: ByteArray, ipHL: Int, dnsResp: ByteArray): ByteArray {
        val udpLen = 8 + dnsResp.size
        val totalLen = ipHL + udpLen
        val pkt = ByteArray(totalLen)

        // Copy and modify IP header
        System.arraycopy(origIp, 0, pkt, 0, ipHL)
        // Swap src/dst IP
        System.arraycopy(origIp, 16, pkt, 12, 4) // dst->src
        System.arraycopy(origIp, 12, pkt, 16, 4) // src->dst
        // Total length
        pkt[2] = (totalLen shr 8).toByte()
        pkt[3] = totalLen.toByte()
        // Zero TTL will be ignored, set reasonable
        pkt[8] = 64

        // UDP header: swap ports
        pkt[ipHL] = origIp[ipHL + 2]
        pkt[ipHL + 1] = origIp[ipHL + 3]
        pkt[ipHL + 2] = origIp[ipHL]
        pkt[ipHL + 3] = origIp[ipHL + 1]
        pkt[ipHL + 4] = (udpLen shr 8).toByte()
        pkt[ipHL + 5] = udpLen.toByte()
        pkt[ipHL + 6] = 0; pkt[ipHL + 7] = 0 // checksum = 0

        // DNS response payload
        System.arraycopy(dnsResp, 0, pkt, ipHL + 8, dnsResp.size)

        // IP header checksum
        pkt[10] = 0; pkt[11] = 0
        var sum = 0
        for (i in 0 until ipHL step 2) {
            sum += (pkt[i].toInt() and 0xFF shl 8) or (pkt[i + 1].toInt() and 0xFF)
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        val cksum = sum.inv() and 0xFFFF
        pkt[10] = (cksum shr 8).toByte()
        pkt[11] = (cksum and 0xFF).toByte()

        return pkt
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Celeste VPN", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Celeste")
                .setContentText("Connected to Celeste")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Celeste")
                .setContentText("Connected to Celeste")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .build()
        }
    }
}
