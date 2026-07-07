package gg.celeste.manager.installer.step.patching

import gg.celeste.manager.R
import gg.celeste.manager.installer.step.Step
import gg.celeste.manager.installer.step.StepGroup
import gg.celeste.manager.installer.step.StepRunner
import gg.celeste.manager.installer.step.download.DownloadBaseStep
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.CRC32

class PatchInviteLinksStep : Step() {

    override val group = StepGroup.PATCHING
    override val nameRes = R.string.step_patch_invites

    private val bundlePath = "assets/index.android.bundle"

    private val patches = listOf(
        "discord.gg/" to "celeste.gg/",
        "hTKzmak" to "vJApcb3",
    )

    override suspend fun run(runner: StepRunner) {
        val baseApk = runner.getCompletedStep<DownloadBaseStep>().workingCopy

        RandomAccessFile(baseApk, "rw").use { raf ->
            val loc = locateStoredEntry(raf, bundlePath)
            if (loc == null) {
                runner.logger.i("$bundlePath not found or not stored, skipping Hermes invite patch")
                return
            }

            val bundle = ByteArray(loc.size)
            raf.seek(loc.dataOffset)
            raf.readFully(bundle)

            val changed = ArrayList<IntArray>()
            for ((from, to) in patches) {
                val needle = from.toByteArray(Charsets.US_ASCII)
                val repl = to.toByteArray(Charsets.US_ASCII)
                require(needle.size == repl.size) { "Hermes patch '$from'->'$to' must be same length" }
                var n = 0
                var i = indexOf(bundle, needle, 0)
                while (i >= 0) {
                    System.arraycopy(repl, 0, bundle, i, repl.size)
                    changed.add(intArrayOf(i, repl.size))
                    n++
                    i = indexOf(bundle, needle, i + needle.size)
                }
                runner.logger.i("Bundle: '$from' -> '$to' x$n")
            }

            if (changed.isEmpty()) {
                runner.logger.i("No invite links found in bundle, nothing to patch")
                return
            }

            val md = MessageDigest.getInstance("SHA-1")
            md.update(bundle, 0, loc.size - 20)
            System.arraycopy(md.digest(), 0, bundle, loc.size - 20, 20)

            val crc = CRC32().apply { update(bundle) }.value

            for (c in changed) {
                raf.seek(loc.dataOffset + c[0])
                raf.write(bundle, c[0], c[1])
            }
            raf.seek(loc.dataOffset + loc.size - 20)
            raf.write(bundle, loc.size - 20, 20)
            writeIntLE(raf, loc.localCrcOffset, crc)
            writeIntLE(raf, loc.centralCrcOffset, crc)

            runner.logger.i("Patched ${changed.size} invite occurrence(s) in place, CRC + SHA-1 footer updated")
        }
    }

    private class EntryLoc(
        val dataOffset: Long,
        val size: Int,
        val localCrcOffset: Long,
        val centralCrcOffset: Long,
    )

    private fun locateStoredEntry(raf: RandomAccessFile, name: String): EntryLoc? {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val fileLen = raf.length()

        val backLen = minOf(fileLen, 65557L).toInt()
        val tail = ByteArray(backLen)
        raf.seek(fileLen - backLen)
        raf.readFully(tail)

        var eocd = -1
        var k = tail.size - 22
        while (k >= 0) {
            if (tail[k] == 0x50.toByte() && tail[k + 1] == 0x4b.toByte() &&
                tail[k + 2] == 0x05.toByte() && tail[k + 3] == 0x06.toByte()
            ) {
                eocd = k
                break
            }
            k--
        }
        if (eocd < 0) return null

        val cdSize = readIntLE(tail, eocd + 12)
        val cdOffset = readIntLE(tail, eocd + 16).toLong()
        if (cdSize <= 0) return null

        val cd = ByteArray(cdSize)
        raf.seek(cdOffset)
        raf.readFully(cd)

        var p = 0
        while (p + 46 <= cd.size) {
            if (!(cd[p] == 0x50.toByte() && cd[p + 1] == 0x4b.toByte() &&
                    cd[p + 2] == 0x01.toByte() && cd[p + 3] == 0x02.toByte())
            ) break

            val method = readShortLE(cd, p + 10)
            val compSize = readIntLE(cd, p + 20)
            val fnLen = readShortLE(cd, p + 28)
            val exLen = readShortLE(cd, p + 30)
            val cmLen = readShortLE(cd, p + 32)
            val localOffset = readIntLE(cd, p + 42).toLong()

            var match = fnLen == nameBytes.size
            if (match) {
                for (j in nameBytes.indices) {
                    if (cd[p + 46 + j] != nameBytes[j]) {
                        match = false
                        break
                    }
                }
            }

            if (match) {
                if (method != 0) return null
                val lh = ByteArray(30)
                raf.seek(localOffset)
                raf.readFully(lh)
                val localFnLen = readShortLE(lh, 26)
                val localExLen = readShortLE(lh, 28)
                val dataOffset = localOffset + 30 + localFnLen + localExLen
                return EntryLoc(dataOffset, compSize, localOffset + 14, cdOffset + p + 16)
            }

            p += 46 + fnLen + exLen + cmLen
        }
        return null
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int): Int {
        val first = needle[0]
        val last = hay.size - needle.size
        var i = from
        while (i <= last) {
            if (hay[i] == first) {
                var m = true
                var j = 1
                while (j < needle.size) {
                    if (hay[i + j] != needle[j]) {
                        m = false
                        break
                    }
                    j++
                }
                if (m) return i
            }
            i++
        }
        return -1
    }

    private fun readIntLE(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or
            ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or
            ((b[o + 3].toInt() and 0xff) shl 24)

    private fun readShortLE(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)

    private fun writeIntLE(raf: RandomAccessFile, pos: Long, v: Long) {
        raf.seek(pos)
        raf.write(
            byteArrayOf(
                (v and 0xff).toByte(),
                ((v shr 8) and 0xff).toByte(),
                ((v shr 16) and 0xff).toByte(),
                ((v shr 24) and 0xff).toByte(),
            )
        )
    }
}
