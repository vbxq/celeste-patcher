package gg.celeste.xposed.modules

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import gg.celeste.xposed.Module
import gg.celeste.xposed.Utils.Log

object NetworkRedirectModule : Module() {

    private val HOST_MAP = mapOf(
        "discord.com" to "alpha.celeste.gg",
        "latency.discord.gg" to "latency.celeste.gg",
        "ptb.discord.com" to "alpha.celeste.gg",
        "canary.discord.com" to "alpha.celeste.gg",
        "www.discord.com" to "alpha.celeste.gg",
        "gateway.discord.gg" to "alpha-gateway.celeste.gg",
        "cdn.discordapp.com" to "cdn.celeste.gg",
        "cdn.discord.com" to "cdn.celeste.gg",
        "media.discordapp.net" to "media.celeste.gg",
        "discordapp.com" to "alpha.celeste.gg",
        "status.discord.com" to "alpha.celeste.gg",
        "discord.gg" to "alpha.celeste.gg",
    )

    private val CDN_HOSTS = setOf(
        "cdn.discordapp.com", "cdn.discord.com",
        "media.discordapp.net", "images.discordapp.net",
    )

    private val CDN_BYPASS_PATHS = listOf(
        "/assets/", "/detectables/", "/changelogs/", "/badge-icons/",
        "/discovery-splashes/", "/hot-spots/", "/embed/",
        "/avatar-decoration-presets/", "/clyde-ai/", "/quests/",
        "/build_overrides/", "/streamer-mode-blocked-words/", "/app-assets/",
        "/app-icons/", "/bad-domains/", "/media/v1/collectibles-shop/",
    )

    private fun isCdnAssetBypass(url: String): Boolean {
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (host !in CDN_HOSTS) return false
        val path = uri.path ?: return false
        return CDN_BYPASS_PATHS.any { path.startsWith(it) }
    }

    private fun isHostOnlyCdn(url: String): Boolean {
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (host !in CDN_HOSTS) return false
        val path = uri.path
        return path.isNullOrEmpty() || path == "/"
    }

    private fun isRemoteAuthHandoff(url: String): Boolean {
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return false
        return uri.path?.startsWith("/ra/") == true
    }

    private fun rewriteUrl(url: String): String {
        if (isCdnAssetBypass(url)) return url
        if (isRemoteAuthHandoff(url)) return url
        var result = url
        for ((from, to) in HOST_MAP) {
            if (result.contains(from)) {
                result = result.replace(from, to)
            }
        }
        return result
    }

    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) { with(packageParam) {
        // method names on Request.Builder (url(String) / url(HttpUrl)) and the whole  HttpUrl.Builder class get renamed on almost every build
        // so hooking them by their obfuscated names (s/t/l,okhttp3.HttpUrl$a, ...) is wacky and breaks
        // that's what broke between 331.x and 332.x:
        // Request.Builder.url(String)  : `s`  ->  `i`
        // Request.Builder.url(HttpUrl) : `t`  ->  removed (inlined to a field write)
        // HttpUrl.Builder              : okhttp3.HttpUrl$a -> hs.s, host() `l` -> `e`
        // class name `okhttp3.HttpUrl` and this constructor's shape are kept stable across versions (bc public OkHttp API surface)
        // so this survives R8 renames
        hookHttpUrlConstructor(classLoader)
        
        hookNetworkingModule(classLoader)
        hookClipboard()
        hookShareIntent()
    } }

    private fun hookHttpUrlConstructor(classLoader: ClassLoader) {
        try {
            val httpUrlClass = classLoader.loadClass("okhttp3.HttpUrl")
            // okhttp3.HttpUrl has exactly one constructor (9 args)
            // locate it by arity + the int port at index 4 instead of an exact parameter type list
            // this way we don't depend on List-vs-ArrayList descriptor details that differ between decompilers/R8 builds
            val ctor = httpUrlClass.declaredConstructors.firstOrNull { c ->
                val p = c.parameterTypes
                p.size == 9 &&
                    p[3] == String::class.java &&
                    p[4] == Integer.TYPE &&
                    p[8] == String::class.java
            } ?: throw NoSuchMethodException("okhttp3.HttpUrl 9-arg constructor not found")

            XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        if (Thread.currentThread().name.contains("OkHttp")) return
                        val url = param.args[8] as? String ?: return
                        if (!url.contains("discord")) return
                        if (isHostOnlyCdn(url)) return

                        val newUrl = rewriteUrl(url)
                        if (newUrl == url) return
                        param.args[8] = newUrl

                        // keep the standalone host field consistent with the new url
                        val host = param.args[3] as? String
                        if (host != null) {
                            val newHost = HOST_MAP[host.lowercase()] ?: rewriteUrl(host)
                            if (newHost != host) param.args[3] = newHost
                        }

                        Log.i("Redirect: $url -> $newUrl")
                    } catch (e: Throwable) {
                        Log.e("HttpUrl redirect hook error; passing through: ${e.message}")
                    }
                }
            })
            Log.i("Hooked okhttp3.HttpUrl.<init> (host+url redirect)")
        } catch (e: Throwable) {
            Log.e("Failed to hook okhttp3.HttpUrl.<init>: ${e.message}")
        }
    }

    private fun hookNetworkingModule(classLoader: ClassLoader) {
        try {
            val networkingModule = classLoader.loadClass("com.facebook.react.modules.network.NetworkingModule")
            for (method in networkingModule.declaredMethods) {
                if (method.name == "sendRequest" || method.name == "sendRequestInternal") {
                    val paramTypes = method.parameterTypes
                    val urlParamIndex = paramTypes.indexOfFirst { it == String::class.java }
                    if (urlParamIndex >= 0) {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                try {
                                    val url = param.args[urlParamIndex] as? String ?: return
                                    val rewritten = rewriteUrl(url)
                                    if (rewritten != url) {
                                        param.args[urlParamIndex] = rewritten
                                        Log.i("RN Redirect: $url -> $rewritten")
                                    }
                                } catch (e: Throwable) {
                                    Log.e("RN redirect hook error; passing through: ${e.message}")
                                }
                            }
                        })
                        Log.i("Hooked NetworkingModule.${method.name}()")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Failed to hook NetworkingModule: ${e.message}")
        }
    }

    private fun hookClipboard() {
        try {
            val clipDataClass = android.content.ClipData::class.java
            val newPlainTextMethod = clipDataClass.getDeclaredMethod(
                "newPlainText", CharSequence::class.java, CharSequence::class.java
            )
            XposedBridge.hookMethod(newPlainTextMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val text = param.args[1]?.toString() ?: return
                    val rewritten = rewriteUrl(text)
                    if (rewritten != text) {
                        param.args[1] = rewritten
                        Log.i("Clipboard: $text -> $rewritten")
                    }
                }
            })
            Log.i("Hooked ClipData.newPlainText()")
        } catch (e: Exception) {
            Log.e("Failed to hook clipboard: ${e.message}")
        }
    }

    private fun hookShareIntent() {
        try {
            val intentClass = android.content.Intent::class.java
            val putExtraMethod = intentClass.getDeclaredMethod(
                "putExtra", String::class.java, String::class.java
            )
            XposedBridge.hookMethod(putExtraMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val key = param.args[0] as? String ?: return
                    if (key != android.content.Intent.EXTRA_TEXT) return
                    val text = param.args[1] as? String ?: return
                    val rewritten = rewriteUrl(text)
                    if (rewritten != text) {
                        param.args[1] = rewritten
                        Log.i("Share intent: $text -> $rewritten")
                    }
                }
            })
            Log.i("Hooked Intent.putExtra() for share")
        } catch (e: Exception) {
            Log.e("Failed to hook share intent: ${e.message}")
        }
    }
}
