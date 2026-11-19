package gg.celeste.xposed.modules

import de.robv.android.xposed.callbacks.XC_LoadPackage
import gg.celeste.xposed.BuildConfig
import gg.celeste.xposed.Constants
import gg.celeste.xposed.Module
import gg.celeste.xposed.Utils.Companion.JSON
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// TODO: Remove this redundant module and merge its functionality into AdditionalBridgeMethodsModule if needed
class PayloadGlobalModule(private val modules: List<Module>) : Module() {
    private companion object {
        const val GLOBAL_NAME = "__PYON_LOADER__"
    }

    private fun getPayloadString(): String = JSON.encodeToString(
        buildJsonObject {
            put("loaderName", Constants.LOADER_NAME)
            put("loaderVersion", BuildConfig.VERSION_NAME)
            @Suppress("DEPRECATION") for (module in modules) module.buildPayload(this)
        })

    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) {
        HookScriptLoaderModule.globalsScript = { "this[${JSON.encodeToString(GLOBAL_NAME)}]=${getPayloadString()}" }
    }
}
