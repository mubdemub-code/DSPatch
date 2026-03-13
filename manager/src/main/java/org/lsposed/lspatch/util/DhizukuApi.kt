package org.lsposed.lspatch.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Looper
import android.os.Handler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runBlocking
import org.lsposed.lspatch.IDhizukuService
import java.util.concurrent.TimeUnit

/**
 * DhizukuApi – Wrapper pour interagir avec Dhizuku SDK.
 * Gère :
 * - Détection de la disponibilité du SDK
 * - État de la permission (avec mise à jour automatique pour Compose)
 * - Demande de permission
 * - Appels synchrones vers le service local (installation APK, commandes shell)
 */
object DhizukuApi {

    // États observables (pour Compose)
    var isAvailable by mutableStateOf(false)
        private set
    var isPermissionGranted by mutableStateOf(false)
        private set

    // Alternative pour ViewModel/LiveData si besoin
    private val _permissionLiveData = MutableLiveData(false)
    val permissionLiveData: LiveData<Boolean> = _permissionLiveData

    private const val BIND_TIMEOUT_MS = 30_000L
    private const val CALL_TIMEOUT_MS = 30_000L

    // Code de requête pour la permission (doit être unique dans l'app)
    const val REQUEST_CODE_DHIZUKU = 1001

    /**
     * Initialisation : détecte le SDK et met à jour les flags.
     * À appeler dans LSPApplication.onCreate().
     */
    fun init(context: Context) {
        try {
            val clazz = Class.forName("com.rosan.dhizuku.api.Dhizuku")
            // Dhizuku.init(context) -> boolean
            val mInit = clazz.getMethod("init", Context::class.java)
            val initOk = (mInit.invoke(null, context) as? Boolean) ?: false
            isAvailable = true
            // Lire l'état de la permission
            updatePermissionStatus()
        } catch (t: Throwable) {
            isAvailable = false
            isPermissionGranted = false
            _permissionLiveData.postValue(false)
        }
    }

    /**
     * Met à jour le statut de la permission via réflexion.
     */
    private fun updatePermissionStatus() {
        try {
            val clazz = Class.forName("com.rosan.dhizuku.api.Dhizuku")
            val mPerm = clazz.getMethod("isPermissionGranted")
            val granted = (mPerm.invoke(null) as? Boolean) ?: false
            isPermissionGranted = granted
            _permissionLiveData.postValue(granted)
        } catch (t: Throwable) {
            isPermissionGranted = false
            _permissionLiveData.postValue(false)
        }
    }

    /**
     * Demande la permission Dhizuku.
     * @param activity L'activity à partir de laquelle lancer la demande
     */
    fun requestPermission(activity: android.app.Activity) {
        try {
            val clazz = Class.forName("com.rosan.dhizuku.api.Dhizuku")
            val method = clazz.getMethod("requestPermission", android.app.Activity::class.java, Int::class.java)
            method.invoke(null, activity, REQUEST_CODE_DHIZUKU)
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    /**
     * À appeler dans onActivityResult ou via un callback pour mettre à jour l'état.
     * @param requestCode Le code de requête
     * @param resultCode Le code résultat (Activity.RESULT_OK si accordé)
     */
    fun handlePermissionResult(requestCode: Int, resultCode: Int) {
        if (requestCode == REQUEST_CODE_DHIZUKU) {
            updatePermissionStatus()
        }
    }

    /**
     * Installation synchrone d'un APK via le service local Dhizuku.
     * Bloque le thread appelant (à lancer en arrière-plan).
     */
    fun installApk(context: Context, apkPath: String, timeoutMs: Long = CALL_TIMEOUT_MS): String {
        if (!isPermissionGranted) {
            return "ERROR: Dhizuku permission not granted"
        }

        val intent = Intent().apply {
            component = ComponentName(context.packageName, "org.lsposed.lspatch.DhizukuService")
        }

        val deferred = CompletableDeferred<String>()
        val handler = Handler(Looper.getMainLooper())

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                try {
                    val aidl = IDhizukuService.Stub.asInterface(service)
                    // Appel dans un thread séparé pour ne pas bloquer le binder
                    Thread {
                        try {
                            val resp = aidl.installApk(apkPath)
                            deferred.complete(resp ?: "NULL_RESPONSE")
                        } catch (e: Throwable) {
                            deferred.complete("EXCEPTION: ${e.stackTraceToString()}")
                        } finally {
                            try { context.unbindService(this) } catch (_: Exception) {}
                        }
                    }.start()
                } catch (e: Throwable) {
                    deferred.complete("EXCEPTION: ${e.stackTraceToString()}")
                    try { context.unbindService(this) } catch (_: Exception) {}
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (!deferred.isCompleted) deferred.complete("ERROR: service disconnected")
            }
        }

        val bindResult = if (Looper.myLooper() == Looper.getMainLooper()) {
            try { context.bindService(intent, conn, Context.BIND_AUTO_CREATE) } catch (t: Throwable) { false }
        } else {
            val bindDeferred = CompletableDeferred<Boolean>()
            handler.post {
                try {
                    val ok = context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
                    bindDeferred.complete(ok)
                } catch (t: Throwable) {
                    bindDeferred.complete(false)
                }
            }
            runBlocking {
                withTimeoutOrNull(BIND_TIMEOUT_MS) { bindDeferred.await() } ?: false
            }
        }

        if (!bindResult) {
            return "ERROR: bindService returned false"
        }

        val result = runBlocking {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } ?: "ERROR: Dhizuku call timed out after ${timeoutMs}ms"

        return result
    }

    /**
     * Exécute une commande shell via le service Dhizuku.
     */
    fun runShellCommand(context: Context, cmd: String, timeoutMs: Long = CALL_TIMEOUT_MS): String {
        if (!isPermissionGranted) {
            return "ERROR: Dhizuku permission not granted"
        }

        val intent = Intent().apply {
            component = ComponentName(context.packageName, "org.lsposed.lspatch.DhizukuService")
        }

        val deferred = CompletableDeferred<String>()
        val handler = Handler(Looper.getMainLooper())

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                try {
                    val aidl = IDhizukuService.Stub.asInterface(service)
                    Thread {
                        try {
                            val resp = aidl.runShellCommand(cmd)
                            deferred.complete(resp ?: "NULL_RESPONSE")
                        } catch (e: Throwable) {
                            deferred.complete("EXCEPTION: ${e.stackTraceToString()}")
                        } finally {
                            try { context.unbindService(this) } catch (_: Exception) {}
                        }
                    }.start()
                } catch (e: Throwable) {
                    deferred.complete("EXCEPTION: ${e.stackTraceToString()}")
                    try { context.unbindService(this) } catch (_: Exception) {}
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (!deferred.isCompleted) deferred.complete("ERROR: service disconnected")
            }
        }

        val bindResult = if (Looper.myLooper() == Looper.getMainLooper()) {
            try { context.bindService(intent, conn, Context.BIND_AUTO_CREATE) } catch (t: Throwable) { false }
        } else {
            val bindDeferred = CompletableDeferred<Boolean>()
            handler.post {
                try {
                    val ok = context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
                    bindDeferred.complete(ok)
                } catch (t: Throwable) {
                    bindDeferred.complete(false)
                }
            }
            runBlocking {
                withTimeoutOrNull(BIND_TIMEOUT_MS) { bindDeferred.await() } ?: false
            }
        }

        if (!bindResult) {
            return "ERROR: bindService returned false"
        }

        val result = runBlocking {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } ?: "ERROR: Dhizuku call timed out after ${timeoutMs}ms"

        return result
    }
}