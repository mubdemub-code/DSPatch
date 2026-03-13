package org.lsposed.lspatch

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.lsposed.hiddenapibypass.HiddenApiBypass
import org.lsposed.lspatch.manager.AppBroadcastReceiver
import org.lsposed.lspatch.util.LSPPackageManager
import org.lsposed.lspatch.util.ShizukuApi
import org.lsposed.lspatch.util.DhizukuApi
import java.io.File

lateinit var lspApp: LSPApplication

class LSPApplication : Application() {

    lateinit var prefs: SharedPreferences
    lateinit var tmpApkDir: File

    val globalScope = CoroutineScope(Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        HiddenApiBypass.addHiddenApiExemptions("")

        lspApp = this
        filesDir.mkdir()
        tmpApkDir = cacheDir.resolve("apk").also { it.mkdir() }
        prefs = lspApp.getSharedPreferences("settings", Context.MODE_PRIVATE)

        // Init Shizuku (existing logic)
        ShizukuApi.init(this)

        // Init Dhizuku via our wrapper (reflection safe).
        // If you prefer to call the SDK directly, add the SDK dependency and replace by Dhizuku.init(this).
        try {
            DhizukuApi.init(this)
        } catch (_: Throwable) {
            // silence: Dhizuku not present or init failed, DhizukuApi.init sets flags accordingly
        }

        AppBroadcastReceiver.register(this)

        globalScope.launch {
            LSPPackageManager.fetchAppList()
        }
    }
}