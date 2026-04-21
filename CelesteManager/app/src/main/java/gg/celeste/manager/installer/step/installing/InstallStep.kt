package gg.celeste.manager.installer.step.installing

import android.content.Context
import gg.celeste.manager.R
import gg.celeste.manager.domain.manager.InstallMethod
import gg.celeste.manager.domain.manager.PreferenceManager
import gg.celeste.manager.installer.Installer
import gg.celeste.manager.installer.session.SessionInstaller
import gg.celeste.manager.installer.shizuku.ShizukuInstaller
import gg.celeste.manager.installer.step.Step
import gg.celeste.manager.installer.step.StepGroup
import gg.celeste.manager.installer.step.StepRunner
import gg.celeste.manager.utils.isMiui
import org.koin.core.component.inject
import java.io.File

/**
 * Installs all the modified splits with the users desired [Installer]
 *
 * @see SessionInstaller
 * @see ShizukuInstaller
 *
 * @param lspatchedDir Where all the patched APKs are
 */
class InstallStep(
    private val lspatchedDir: File
): Step() {

    private val preferences: PreferenceManager by inject()
    private val context: Context by inject()

    override val group = StepGroup.INSTALLING
    override val nameRes = R.string.step_installing

    override suspend fun run(runner: StepRunner) {
        runner.logger.i("Installing apks")
        val files = lspatchedDir.listFiles()
            ?.takeIf { it.isNotEmpty() }
            ?: throw Error("Missing APKs from LSPatch step; failure likely")

        val installer: Installer = when (preferences.installMethod) {
            InstallMethod.DEFAULT -> SessionInstaller(context)
            InstallMethod.SHIZUKU -> ShizukuInstaller(context)
        }

        installer.installApks(silent = !isMiui, *files)
    }

}