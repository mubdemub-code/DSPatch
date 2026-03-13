// src/main/aidl/org/lsposed/lspatch/IDhizukuService.aidl
package org.lsposed.lspatch;

/**
 * Interface AIDL exposée par le service Dhizuku/Host pour exécuter
 * commandes shell privilégiées et installer des APKs.
 *
 * NOTE:
 *  - Les appels sont SYNCHRONES (ils retournent une String). Ne les appelez
 *    pas depuis le thread UI sans effectuer le binding / l'appel dans un thread.
 *  - Si vous avez besoin d'appels asynchrones, implémentez un callback AIDL séparé
 *    ou lancez l'appel sur un worker thread côté client.
 */
interface IDhizukuService {
    /**
     * Exécute une commande shell. Exemple : "pm install -r /path/to/app.apk"
     * Retourne une chaîne formatée (stdout + stderr / code de sortie) ou un message d'erreur.
     */
    String runShellCommand(String cmd);

    /**
     * Installe un APK en utilisant pm (ou l'API Dhizuku si disponible).
     * apkPath doit être un chemin absolu accessible par le service.
     * Retourne la sortie de la commande (ou message d'erreur).
     */
    String installApk(String apkPath);

    /**
     * Demande l'arrêt propre du service (ne tue pas l'application entière).
     */
    void destroy();
}