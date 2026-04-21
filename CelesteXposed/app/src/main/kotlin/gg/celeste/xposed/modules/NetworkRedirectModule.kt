package gg.celeste.xposed.modules

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import gg.celeste.xposed.Module
import gg.celeste.xposed.Utils.Log

object NetworkRedirectModule : Module() {

    private val HOST_MAP = mapOf(
        "discord.com" to "alpha.celeste.gg",
        "ptb.discord.com" to "alpha.celeste.gg",
        "canary.discord.com" to "alpha.celeste.gg",
        "www.discord.com" to "alpha.celeste.gg",
        "gateway.discord.gg" to "alpha-gateway.celeste.gg",
        "cdn.discordapp.com" to "cdn.celeste.gg",
        "cdn.discord.com" to "cdn.celeste.gg",
        "media.discordapp.net" to "media.celeste.gg",
        "discordapp.com" to "alpha.celeste.gg",
        "status.discord.com" to "alpha.celeste.gg",
    )

    private fun rewriteUrl(url: String): String {
        var result = url
        for ((from, to) in HOST_MAP) {
            if (result.contains(from)) {
                result = result.replace(from, to)
            }
        }
        return result
    }

    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) { with(packageParam) {
        try {
            val builderClass = classLoader.loadClass("okhttp3.Request\$Builder")
            val urlStringMethod = builderClass.getDeclaredMethod("s", String::class.java)
            XposedBridge.hookMethod(urlStringMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val original = param.args[0] as? String ?: return
                    val rewritten = rewriteUrl(original)
                    if (rewritten != original) {
                        param.args[0] = rewritten
                        Log.i("Redirect: $original -> $rewritten")
                    }
                }
            })
            Log.i("Hooked Request.Builder.s(String)")
        } catch (e: Exception) {
            Log.e("Failed to hook Request.Builder.s: ${e.message}")
        }

        try {
            val builderClass = classLoader.loadClass("okhttp3.Request\$Builder")
            val httpUrlClass = classLoader.loadClass("okhttp3.HttpUrl")
            val urlHttpUrlMethod = builderClass.getDeclaredMethod("t", httpUrlClass)
            XposedBridge.hookMethod(urlHttpUrlMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val httpUrl = param.args[0] ?: return
                    val original = httpUrl.toString()
                    val rewritten = rewriteUrl(original)
                    if (rewritten != original) {
                        try {
                            val stringMethod = param.thisObject::class.java.getDeclaredMethod("s", String::class.java)
                            stringMethod.invoke(param.thisObject, rewritten)
                            param.result = param.thisObject
                        } catch (e: Exception) {
                            Log.e("HttpUrl redirect fallback failed: ${e.message}")
                        }
                    }
                }
            })
            Log.i("Hooked Request.Builder.t(HttpUrl)")
        } catch (e: Exception) {
            Log.e("Failed to hook Request.Builder.t: ${e.message}")
        }

        try {
            val httpUrlBuilderClass = classLoader.loadClass("okhttp3.HttpUrl\$a")
            val hostMethod = httpUrlBuilderClass.getDeclaredMethod("l", String::class.java)
            XposedBridge.hookMethod(hostMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val original = param.args[0] as? String ?: return
                    val replacement = HOST_MAP[original.lowercase()]
                    if (replacement != null) {
                        param.args[0] = replacement
                        Log.i("HttpUrl.Builder host: $original -> $replacement")
                    }
                }
            })
            Log.i("Hooked HttpUrl.Builder.l(String)")
        } catch (e: Exception) {
            Log.e("Failed to hook HttpUrl.Builder.l: ${e.message}")
        }

        try {
            val clientBuilderClass = classLoader.loadClass("okhttp3.OkHttpClient\$Builder")
            val buildMethod = clientBuilderClass.getDeclaredMethod("c")
            XposedBridge.hookMethod(buildMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val builder = param.thisObject

                        val trustManager = object : javax.net.ssl.X509TrustManager {
                            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                        }

                        val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
                        sslContext.init(null, arrayOf<javax.net.ssl.TrustManager>(trustManager), null)

                        val hvMethod = clientBuilderClass.getDeclaredMethod("j", javax.net.ssl.HostnameVerifier::class.java)
                        hvMethod.invoke(builder, javax.net.ssl.HostnameVerifier { _, _ -> true })

                        val ssfMethod = clientBuilderClass.getDeclaredMethod("U",
                            javax.net.ssl.SSLSocketFactory::class.java,
                            javax.net.ssl.X509TrustManager::class.java
                        )
                        ssfMethod.invoke(builder, sslContext.socketFactory, trustManager)
                    } catch (_: Exception) {}
                }
            })
            Log.i("Hooked OkHttpClient.Builder.c()")
        } catch (e: Exception) {
            Log.e("Failed to hook OkHttpClient.Builder.c: ${e.message}")
            try {
                val cls = classLoader.loadClass("okhttp3.OkHttpClient\$Builder")
                val methods = cls.declaredMethods.filter { it.parameterTypes.isEmpty() && it.returnType.name.contains("OkHttpClient") }
                for (m in methods) {
                    Log.i("OkHttpClient.Builder candidate build method: ${m.name}()")
                }
            } catch (_: Exception) {}
        }
    } }
}
