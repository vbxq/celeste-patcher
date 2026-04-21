package gg.celeste.manager.installer.step.download

import androidx.compose.runtime.Stable
import gg.celeste.manager.R
import gg.celeste.manager.installer.step.download.base.DownloadStep
import java.io.File

/**
 * Downloads the CelesteXposed module
 *
 * https://github.com/celeste-gg/CelesteXposed
 */
@Stable
class DownloadCelesteStep(
    workingDir: File
): DownloadStep() {

    override val nameRes = R.string.step_dl_vd

    override val url: String = "https://github.com/vbxq/celeste-patcher/releases/latest/download/CelesteXposed.apk"
    override val destination = preferenceManager.moduleLocation
    override val workingCopy = workingDir.resolve("xposed.apk")

}