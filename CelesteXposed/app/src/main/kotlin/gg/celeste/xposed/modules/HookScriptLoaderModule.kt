package gg.celeste.xposed.modules

import android.content.res.AssetManager
import android.content.res.XModuleResources
import android.content.Context
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import gg.celeste.xposed.Constants
import gg.celeste.xposed.HookStateHolder
import gg.celeste.xposed.Module
import gg.celeste.xposed.Utils.Log
import gg.celeste.xposed.modules.HookScriptLoaderModule.PRELOADS_DIR
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.io.File
import java.lang.reflect.Method

/**
 * Hooks React Native's script loading methods to load custom scripts and bundles.
 *
 * Preload scripts should be placed in the [PRELOADS_DIR] directory inside the module's files directory.
 *
 * The main bundle should be placed in the [Constants.CACHE_DIR] directory named [Constants.MAIN_SCRIPT_FILE].
 * If the bundle file does not exist, it will attempt to load `assets://revenge.bundle` from the module's assets.
 */
object HookScriptLoaderModule : Module() {
    private lateinit var preloadsDir: File
    private lateinit var mainScript: File

    // Directory to read loader config from when checking disable flag
    private var moduleFilesDir: File? = null

    private lateinit var modulePath: String
    private lateinit var resources: XModuleResources

    const val PRELOADS_DIR = "preloads"

    override fun onInit(startupParam: IXposedHookZygoteInit.StartupParam) {
        this@HookScriptLoaderModule.modulePath = startupParam.modulePath
    }

    override fun onContext(context: android.content.Context) {
        // Keep a reference to the module's files directory so we can check the loader config
        try {
            val dir = File(context.dataDir, Constants.FILES_DIR).apply { asDir() }
            moduleFilesDir = dir
        } catch (_: Exception) {
            // best-effort only
        }
    }

    // set by PayloadGlobalModule, evaluated before every other injected script
    @Volatile
    var globalsScript: (() -> String)? = null

    private class LoaderTarget(
        val loadFromAssets: Method,
        val loadFromFile: Method,
        val runFile: (thisObject: Any?, args: Array<Any?>, path: String) -> Unit,
        val runAsset: (thisObject: Any?, args: Array<Any?>, assets: AssetManager, url: String) -> Unit,
    )

    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) = with(packageParam) {
        val cacheDir = File(appInfo.dataDir, Constants.CACHE_DIR).apply { asDir() }
        val filesDir = File(appInfo.dataDir, Constants.FILES_DIR).apply { asDir() }

        preloadsDir = File(filesDir, PRELOADS_DIR).apply { asDir() }
        mainScript = File(cacheDir, Constants.MAIN_SCRIPT_FILE).apply { asFile() }

        val targets = listOf(
            "com.facebook.react.runtime.ReactInstance\$loadJSBundle$1",
            "com.facebook.react.runtime.ReactInstance$1",
            "com.facebook.react.bridge.CatalystInstanceImpl"
        ).mapNotNull { classLoader.safeLoadClass(it) }.mapNotNull { delegateTarget(it) }
            .ifEmpty { listOfNotNull(reactInstanceTarget(classLoader)) }

        if (targets.isEmpty()) Log.e("No React Native script loader found, bundle will not be injected")
        targets.forEach { hook(it) }
    }

    private fun delegateTarget(instance: Class<*>): LoaderTarget? = runCatching {
        val fromAssets = instance.method(
            "loadScriptFromAssets", AssetManager::class.java, String::class.java, Boolean::class.javaPrimitiveType
        )
        val fromFile = instance.method(
            "loadScriptFromFile", String::class.java, String::class.java, Boolean::class.javaPrimitiveType
        )
        LoaderTarget(
            fromAssets, fromFile,
            runFile = { self, args, path ->
                XposedBridge.invokeOriginalMethod(fromFile, self, arrayOf(path, path, args[2]))
            },
            runAsset = { self, args, assets, url ->
                XposedBridge.invokeOriginalMethod(fromAssets, self, arrayOf(assets, url, args[2]))
            },
        )
    }.onFailure {
        Log.e("Failed to resolve script loading methods in ${instance.name}:", it)
    }.getOrNull()

    // discord 352+ is r8 obfuscated: the JSBundleLoaderDelegate is an anonymous class with a random name,
    // but it reaches ReactInstance's private natives through static accessors whose signatures are stable
    private fun reactInstanceTarget(classLoader: ClassLoader): LoaderTarget? = runCatching {
        val reactInstance = classLoader.loadClass("com.facebook.react.runtime.ReactInstance")
        val statics = reactInstance.declaredMethods.filter { java.lang.reflect.Modifier.isStatic(it.modifiers) }
        val fromAssets = statics.single {
            it.parameterTypes.contentEquals(arrayOf(reactInstance, AssetManager::class.java, String::class.java))
        }.apply { isAccessible = true }
        val fromFile = statics.single {
            it.parameterTypes.contentEquals(arrayOf(reactInstance, String::class.java, String::class.java))
        }.apply { isAccessible = true }
        Log.i("Using ReactInstance accessors ${fromAssets.name}/${fromFile.name} as script loader")
        LoaderTarget(
            fromAssets, fromFile,
            runFile = { _, args, path -> XposedBridge.invokeOriginalMethod(fromFile, null, arrayOf(args[0], path, path)) },
            runAsset = { _, args, assets, url ->
                XposedBridge.invokeOriginalMethod(fromAssets, null, arrayOf(args[0], assets, url))
            },
        )
    }.onFailure {
        Log.e("Failed to resolve ReactInstance script loader accessors:", it)
    }.getOrNull()

    private fun hook(target: LoaderTarget) {
        target.loadFromAssets.hook {
            before {
                Log.i("Received call to ${target.loadFromAssets.name}: ${args.drop(1).joinToString()}")
                runCustomScripts(target)
            }
        }

        target.loadFromFile.hook {
            before {
                Log.i("Received call to ${target.loadFromFile.name}: ${args.joinToString()}")
                runCustomScripts(target)
            }
        }
    }

    private fun HookScope.runCustomScripts(target: LoaderTarget) {
        Log.i("Running custom scripts...")

        runBlocking {
            val ready = async { HookStateHolder.readyDeferred.join() }
            val isCustomUrl = UpdaterModule.isCustomUrlEnabled

            if (!mainScript.exists() || isCustomUrl) {
                val reason = if (isCustomUrl) "Custom URL enabled" else "Main script does not exist"
                Log.i("$reason, downloading before load...")
                val download =
                    async { UpdaterModule.downloadScript(showUpdateDialog = false).join() }
                awaitAll(ready, download)
            } else {
                Log.i("Main script exists, updating in background...")
                UpdaterModule.downloadScript(showUpdateDialog = true)
                ready.await()
            }
        }

        val runScriptFile = { file: File ->
            Log.i("Loading script: ${file.absolutePath}")
            target.runFile(thisObject, args, file.absolutePath)
        }

        try {
            globalsScript?.let { script ->
                val file = File(preloadsDir.parentFile, "rv_globals.js")
                file.writeText(script())
                try {
                    runScriptFile(file)
                } finally {
                    file.delete()
                }
            }

            preloadsDir.walk().filter { it.isFile }.forEach(runScriptFile)

            val injectionDisabled = try {
                UpdaterModule.isInjectionDisabled()
            } catch (e: Throwable) {
                Log.e("Failed to check injection disabled flag: ${e.message}")
                false
            }

            if (injectionDisabled) {
                Log.i("Bundle injection disabled by loader config - skipping main bundle execution")
            } else {
                if (mainScript.exists()) runScriptFile(mainScript)
                else {
                    Log.i("Main script does not exist, falling back")

                    if (!::resources.isInitialized) resources = XModuleResources.createInstance(modulePath, null)

                    target.runAsset(thisObject, args, resources.assets, "assets://revenge.bundle")
                }
            }
        } catch (e: Throwable) {
            Log.e("Unable to run scripts:", e)
        }
    }
}
