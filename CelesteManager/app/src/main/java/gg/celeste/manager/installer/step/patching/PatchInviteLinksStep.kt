package gg.celeste.manager.installer.step.patching

import com.github.diamondminer88.zip.ZipCompression
import com.github.diamondminer88.zip.ZipReader
import com.github.diamondminer88.zip.ZipWriter
import gg.celeste.manager.R
import gg.celeste.manager.installer.step.Step
import gg.celeste.manager.installer.step.StepGroup
import gg.celeste.manager.installer.step.StepRunner
import gg.celeste.manager.installer.step.download.DownloadBaseStep
import java.security.MessageDigest

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

        val bundle = ZipReader(baseApk).use { it.openEntry(bundlePath)?.read() }
        if (bundle == null) {
            runner.logger.i("$bundlePath not found; skipping Hermes invite patch")
            return
        }

        var total = 0
        for ((from, to) in patches) {
            val needle = from.toByteArray(Charsets.US_ASCII)
            val repl = to.toByteArray(Charsets.US_ASCII)
            require(needle.size == repl.size) { "Hermes patch '$from'->'$to' must be same length" }
            val n = replaceBytes(bundle, needle, repl)
            runner.logger.i("Bundle: '$from' -> '$to' x$n")
            total += n
        }

        if (total == 0) {
            runner.logger.i("No invite links found in bundle; nothing to write")
            return
        }

        val len = bundle.size
        val md = MessageDigest.getInstance("SHA-1")
        md.update(bundle, 0, len - 20)
        val hash = md.digest()
        System.arraycopy(hash, 0, bundle, len - 20, 20)
        runner.logger.i("Recomputed Hermes SHA-1 footer")

        ZipWriter(baseApk, /* append = */ true).use { zip ->
            zip.deleteEntry(bundlePath, /* fillVoid = */ true)
            zip.writeEntry(bundlePath, bundle, ZipCompression.NONE, 4)
        }
    }

    private fun replaceBytes(buf: ByteArray, needle: ByteArray, repl: ByteArray): Int {
        var count = 0
        var i = 0
        val last = buf.size - needle.size
        while (i <= last) {
            var match = true
            for (j in needle.indices) {
                if (buf[i + j] != needle[j]) {
                    match = false
                    break
                }
            }
            if (match) {
                System.arraycopy(repl, 0, buf, i, repl.size)
                count++
                i += needle.size
            } else {
                i++
            }
        }
        return count
    }
}
