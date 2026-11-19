package gg.celeste.xposed

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.callbacks.XC_LoadPackage
import gg.celeste.xposed.Utils.Log
import gg.celeste.xposed.modules.*
import gg.celeste.xposed.modules.appearance.FontsModule
import gg.celeste.xposed.modules.appearance.SysColorsModule
import gg.celeste.xposed.modules.appearance.ThemesModule
import gg.celeste.xposed.modules.bridge.AdditionalBridgeMethodsModule
import gg.celeste.xposed.modules.bridge.BridgeModule
import gg.celeste.xposed.modules.no_track.BlockCrashReportingModule
import gg.celeste.xposed.modules.no_track.BlockDeepLinksTrackingModule
import kotlinx.coroutines.CompletableDeferred

object HookStateHolder {
    /**
     * Whether all hooks are completed, and we are ready to load the JS bundle.
     */
    val readyDeferred = CompletableDeferred<Unit>()

    /**
     * Whether we have successfully received a [Context] yet.
     * Sometimes the app process is recreated and Xposed hooks way too late for us to get [Context] from [ContextWrapper.attachBaseContext].
     * But since Xposed hooks before [Activity.onCreate], we can still get it from there and still initialize properly.
     */
    @Volatile
    var gotContext = false
}

class Main : Module(), IXposedHookLoadPackage, IXposedHookZygoteInit {
    private var hooked = false
    private val modules = mutableListOf(
        NetworkRedirectModule,
        HookScriptLoaderModule,
        BridgeModule,
        AdditionalBridgeMethodsModule,
        PluginsModule(),
        UpdaterModule,
        FixResourcesModule,
        BlockDeepLinksTrackingModule,
        BlockCrashReportingModule,
        LogBoxModule,
        CacheModule,
        FontsModule,
        ThemesModule,
        SysColorsModule
    )

    init {
        modules += PayloadGlobalModule(modules)
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        for (module in modules) module.onInit(startupParam)
    }

    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) = with(param) {
        if (hooked) return

        val activityHooked = java.util.concurrent.atomic.AtomicBoolean(false)

        ContextWrapper::class.java.hookMethod("attachBaseContext", Context::class.java) {
            after {
                val ctx = args[0] as Context
                if (activityHooked.compareAndSet(false, true)) hookLauncherActivity(classLoader, ctx)
                HookStateHolder.gotContext = true
                Log.i("Received Context")
                this@Main.onContext(ctx)
            }
        }

        this@Main.onLoad(param)

        hooked = true
    }

    // discord 352+ is r8 obfuscated and ReactActivity is gone, so resolve the launcher activity at runtime
    private fun hookLauncherActivity(classLoader: ClassLoader, ctx: Context) {
        val name = try {
            launcherActivityName(ctx)
        } catch (e: Throwable) {
            Log.w("Unable to resolve the launcher activity, falling back to ${Constants.TARGET_ACTIVITY}", e)
            Constants.TARGET_ACTIVITY
        }

        val onCreate = try {
            var cls: Class<*>? = classLoader.loadClass(name)
            var found: java.lang.reflect.Method? = null
            while (cls != null && found == null) {
                found = cls.declaredMethods.firstOrNull {
                    it.name == "onCreate" && it.parameterTypes.contentEquals(arrayOf(Bundle::class.java))
                }
                cls = cls.superclass
            }
            found ?: throw NoSuchMethodException("onCreate(Bundle) not found for $name")
        } catch (e: Throwable) {
            Log.e("Unable to hook launcher activity $name", e)
            return
        }

        Log.i("Hooking launcher activity $name (onCreate in ${onCreate.declaringClass.name})")
        onCreate.hook {
            after {
                if (thisObject?.javaClass?.name != name) return@after
                val act = thisObject as Activity
                Log.i("Received Activity")

                if (!HookStateHolder.gotContext) {
                    Log.w("Activity created before we got Context, process may have been recreated!")
                    this@Main.onContext(act.applicationContext)
                }

                this@Main.onActivity(act)
                HookStateHolder.readyDeferred.complete(Unit)
            }
        }
    }

    private fun launcherActivityName(ctx: Context): String {
        val pm = ctx.packageManager
        val intent = pm.getLaunchIntentForPackage(ctx.packageName) ?: error("No launch intent for ${ctx.packageName}")
        val info = pm.resolveActivity(intent, 0)?.activityInfo ?: error("Launch intent for ${ctx.packageName} doesn't resolve")
        return info.targetActivity ?: info.name
    }

    // one module failing on a new discord build must not take the others down with it
    private inline fun forEachModule(stage: String, block: (Module) -> Unit) {
        for (module in modules) {
            try {
                block(module)
            } catch (e: Throwable) {
                Log.e("${module.javaClass.simpleName}.$stage failed", e)
            }
        }
    }

    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) = forEachModule("onLoad") { it.onLoad(packageParam) }

    override fun onContext(context: Context) = forEachModule("onContext") { it.onContext(context) }

    override fun onActivity(activity: Activity) = forEachModule("onActivity") { it.onActivity(activity) }
}
