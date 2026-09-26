package com.hush.net

import android.content.Context
import android.os.Build
import com.hush.HLog

/**
 * Spike for the Android 16 Ranging API (Bluetooth Channel Sounding). Reflection only, so the app still
 * builds against compileSdk 35 and simply reports "unavailable" on anything older. Logs what the phone
 * says it supports; the real ranging session is the next step once this reads positive.
 */
object BleRangingProbe {
    fun run(context: Context) {
        if (Build.VERSION.SDK_INT < 36) { HLog.d("BLE ranging probe: needs Android 16, have API ${Build.VERSION.SDK_INT}"); return }
        try {
            val mgrClass = Class.forName("android.ranging.RangingManager")
            val mgr = context.getSystemService(mgrClass) ?: run { HLog.d("BLE ranging probe: RangingManager service is null"); return }
            // registerCapabilitiesCallback(Executor, RangingManager.RangingCapabilitiesCallback)
            val cbClass = Class.forName("android.ranging.RangingManager\$RangingCapabilitiesCallback")
            val handler = java.lang.reflect.InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "hashCode" -> return@InvocationHandler System.identityHashCode(proxy)
                    "equals" -> return@InvocationHandler (args != null && args.isNotEmpty() && args[0] === proxy)
                    "toString" -> return@InvocationHandler "HushRangingCapabilitiesCallback"
                }
                if (method.name == "onRangingCapabilities" && args != null && args.isNotEmpty()) {
                    val caps = args[0]
                    try {
                        val techs = caps.javaClass.getMethod("getSupportedTechnologies").invoke(caps)
                        HLog.d("BLE ranging probe: supported technologies = $techs (0=UWB,1=BLE CS,2=WiFi NAN RTT,3=BLE RSSI)")
                        val csCaps = try { caps.javaClass.getMethod("getCsCapabilities").invoke(caps) } catch (_: Exception) { null }
                        HLog.d("BLE ranging probe: channel sounding capabilities = $csCaps")
                    } catch (e: Exception) {
                        HLog.d("BLE ranging probe: capabilities object $caps ($e)")
                    }
                }
                null
            }
            val proxy = java.lang.reflect.Proxy.newProxyInstance(cbClass.classLoader, arrayOf(cbClass), handler)
            val register = mgrClass.getMethod("registerCapabilitiesCallback", java.util.concurrent.Executor::class.java, cbClass)
            register.invoke(mgr, java.util.concurrent.Executor { it.run() }, proxy)
            HLog.d("BLE ranging probe: callback registered, waiting for capabilities")
        } catch (e: Throwable) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            HLog.d("BLE ranging probe failed: $cause")
            cause.stackTrace.take(4).forEach { HLog.d("   at $it") }
        }
    }
}
