package gg.celeste.manager.ui.viewmodel.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import gg.celeste.manager.BuildConfig
import gg.celeste.manager.domain.manager.DownloadManager
import gg.celeste.manager.domain.manager.InstallManager
import gg.celeste.manager.domain.manager.InstallMethod
import gg.celeste.manager.domain.manager.PreferenceManager
import gg.celeste.manager.domain.repository.RestRepository
import gg.celeste.manager.installer.Installer
import gg.celeste.manager.installer.session.SessionInstaller
import gg.celeste.manager.installer.shizuku.ShizukuInstaller
import gg.celeste.manager.network.dto.Release
import gg.celeste.manager.network.utils.CommitsPagingSource
import gg.celeste.manager.network.utils.dataOrNull
import gg.celeste.manager.network.utils.ifSuccessful
import gg.celeste.manager.utils.DiscordVersion
import gg.celeste.manager.utils.isMiui
import kotlinx.coroutines.launch
import java.io.File

// help me
fun versionStringToCode(version: String): Int {
    val parts = version.split('.').mapNotNull { it.toIntOrNull() }
    val major = parts.getOrNull(0) ?: 0
    val minor = parts.getOrNull(1) ?: 0
    val patch = parts.getOrNull(2) ?: 0
    return major * 10000 + minor * 100 + patch
}

class HomeViewModel(
    private val repo: RestRepository,
    val context: Context,
    val prefs: PreferenceManager,
    val installManager: InstallManager,
    private val downloadManager: DownloadManager
) : ScreenModel {

    private val cacheDir = context.externalCacheDir ?: File(
        Environment.getExternalStorageDirectory(),
        Environment.DIRECTORY_DOWNLOADS
    ).resolve("CelesteManager").also { it.mkdirs() }

    var discordVersions by mutableStateOf<Map<DiscordVersion.Type, DiscordVersion?>?>(null)
        private set

    var release by mutableStateOf<Release?>(null)
        private set

    var showUpdateDialog by mutableStateOf(false)
    var isUpdating by mutableStateOf(false)
    val commits = Pager(PagingConfig(pageSize = 20)) { CommitsPagingSource(repo) }.flow.cachedIn(screenModelScope)

    init {
        getDiscordVersions()
        checkForUpdate()
    }

    fun getDiscordVersions() {
        screenModelScope.launch {
            discordVersions = repo.getLatestDiscordVersions().dataOrNull
            if (prefs.autoClearCache) autoClearCache()
        }
    }

    fun launchCeleste() {
        installManager.current?.let {
            val intent = context.packageManager.getLaunchIntentForPackage(it.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    fun uninstallCeleste() {
        installManager.uninstall()
    }

    fun launchCelesteInfo() {
        installManager.current?.let {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                data = Uri.parse("package:${it.packageName}")
                context.startActivity(this)
            }
        }
    }

    private fun autoClearCache() {
        val currentVersion =
            DiscordVersion.fromVersionCode(installManager.current?.longVersionCode.toString()) ?: return
        val latestVersion = when {
            prefs.discordVersion.isBlank() -> discordVersions?.get(prefs.channel)
            else -> DiscordVersion.fromVersionCode(prefs.discordVersion)
        } ?: return

        if (latestVersion > currentVersion) {
            for (file in (context.externalCacheDir ?: context.cacheDir).listFiles()
                ?: emptyArray()) {
                if (file.isDirectory) file.deleteRecursively()
            }
        }
    }

    private fun checkForUpdate() {
        screenModelScope.launch {
            release = repo.getLatestRelease("celeste-gg/CelesteManager").dataOrNull
//            release?.let {
//                val cleanTag = it.tagName.removePrefix("v")
//                val tagCode = versionStringToCode(cleanTag)
//                showUpdateDialog = tagCode > BuildConfig.VERSION_CODE
//            }
            release?.let {
                showUpdateDialog = it.tagName.toInt() > BuildConfig.VERSION_CODE
            }
            repo.getLatestRelease("celeste-gg/CelesteXposed").ifSuccessful {
                if (prefs.moduleVersion != it.tagName) {
                    prefs.moduleVersion = it.tagName
                    val module = File(cacheDir, "xposed.apk")
                    if (module.exists()) module.delete()
                }
            }
        }
    }

    fun downloadAndInstallUpdate() {
        screenModelScope.launch {
            val update = File(cacheDir, "update.apk")
            if (update.exists()) update.delete()
            isUpdating = true
            downloadManager.downloadUpdate(update)
            isUpdating = false

            val installer: Installer = when (prefs.installMethod) {
                InstallMethod.DEFAULT -> SessionInstaller(context)
                InstallMethod.SHIZUKU -> ShizukuInstaller(context)
            }

            installer.installApks(silent = !isMiui, update)
        }
    }

}