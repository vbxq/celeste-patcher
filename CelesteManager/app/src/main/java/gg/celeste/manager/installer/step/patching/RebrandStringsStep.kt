package gg.celeste.manager.installer.step.patching

import com.github.diamondminer88.zip.ZipReader
import com.github.diamondminer88.zip.ZipWriter
import gg.celeste.manager.R
import gg.celeste.manager.installer.step.Step
import gg.celeste.manager.installer.step.StepGroup
import gg.celeste.manager.installer.step.StepRunner
import gg.celeste.manager.installer.step.download.DownloadBaseStep
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class RebrandStringsStep : Step() {

    override val group = StepGroup.PATCHING
    override val nameRes = R.string.step_rebrand

    private val json = Json { isLenient = true }

    private val replacements = listOf(
        "Discord's" to "Celeste's",
        "hTKzmak" to "vJApcb3",
        "discord.com" to "celeste.gg",
        "discord.gg" to "celeste.gg",
        "Discord" to "Celeste",
        "Nitro" to "Supporter",
    )

    private fun rebrand(s: String): String {
        var out = s
        for ((from, to) in replacements) out = out.replace(from, to)
        return out
    }

    private fun rebrandElement(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { (_, v) -> rebrandElement(v) }) // keys untouched
        is JsonArray -> JsonArray(e.map { rebrandElement(it) })
        is JsonPrimitive -> if (e.isString) JsonPrimitive(rebrand(e.content)) else e
    }

    override suspend fun run(runner: StepRunner) {
        val baseApk = runner.getCompletedStep<DownloadBaseStep>().workingCopy

        val targets = ZipReader(baseApk).use { zip ->
            zip.entryNames.filter {
                it.startsWith("res/raw/cache_intl") && it.endsWith("compiledmessages.jsona")
            }
        }
        runner.logger.i("Scanning ${targets.size} i18n message files for rebranding")

        val patched = HashMap<String, ByteArray>()
        ZipReader(baseApk).use { zip ->
            for (name in targets) {
                val bytes = zip.openEntry(name)?.read() ?: continue
                val text = bytes.toString(Charsets.UTF_8)
                if (!text.contains("Discord") && !text.contains("Nitro") &&
                    !text.contains("discord.") && !text.contains("hTKzmak")
                ) continue
                runCatching {
                    val newText = rebrandElement(json.parseToJsonElement(text)).toString()
                    if (newText != text) patched[name] = newText.toByteArray(Charsets.UTF_8)
                }.onFailure { runner.logger.i("Skipped $name (parse failed: ${it.message})") }
            }
        }
        runner.logger.i("Rebranding ${patched.size} i18n files")
        if (patched.isEmpty()) return
        ZipWriter(baseApk, /* append = */ true).use { zip ->
            for ((name, bytes) in patched) {
                zip.deleteEntry(name)
                zip.writeEntry(name, bytes)
            }
        }
    }
}
