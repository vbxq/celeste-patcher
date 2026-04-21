package gg.celeste.manager.installer.step.patching

import gg.celeste.manager.R
import gg.celeste.manager.installer.step.Step
import gg.celeste.manager.installer.step.StepGroup
import gg.celeste.manager.installer.step.StepRunner
import gg.celeste.manager.installer.step.download.DownloadCelesteStep
import gg.celeste.manager.installer.util.Patcher
import java.io.File

/**
 * Uses LSPatch to inject the CelesteXposed module into Discord
 *
 * @param signedDir The signed apks to patch
 * @param lspatchedDir Output directory for LSPatch
 */
class AddCelesteStep(
    private val signedDir: File,
    private val lspatchedDir: File
) : Step() {

    override val group = StepGroup.PATCHING
    override val nameRes = R.string.step_add_vd

    override suspend fun run(runner: StepRunner) {
        val celeste = runner.getCompletedStep<DownloadCelesteStep>().workingCopy

        runner.logger.i("Adding CelesteXposed module with LSPatch")
        val files = signedDir.listFiles()
            ?.takeIf { it.isNotEmpty() }
            ?: throw Error("Missing APKs from signing step")

        Patcher.patch(
            runner.logger,
            outputDir = lspatchedDir,
            apkPaths = files.map { it.absolutePath },
            embeddedModules = listOf(celeste.absolutePath)
        )
    }

}