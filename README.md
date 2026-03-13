# 🛡️ Imperium Framework

> **Total authority over Android application patching.**
> Une solution de gestion de patchs nouvelle génération fusionnant la puissance de **Dhizuku** et la flexibilité de **LSPatch**.

[![Build](https://img.shields.io/github/actions/workflow/status/mubdemub-code/ImperiumFramework/main.yml?branch=master&logo=github&label=Build)](https://github.com/mubdemub-code/ImperiumFramework/actions)
[![License](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](./LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9+-purple?logo=kotlin)](https://kotlinlang.org/)

---

### ✨ Points Forts

* **⚡ Souveraineté Système** : Exploite l'API Dhizuku (Device Owner) pour des installations silencieuses sans Root.
* **🔄 Moteur Hybride** : Basculement intelligent entre **Dhizuku → Shizuku → Root**.
* **🎨 UI Moderne** : Interface fluide sous **Material 3** et Jetpack Compose.
* **🔑 Signature Custom** : Gestion intégrée des keystores pour signer vos APKs patchés.
* **📝 Logs en Temps Réel** : Suivi détaillé de l'injection pour un débogage facilité.

---

### 🚀 Démarrage Rapide

**Prérequis :** Android 9+ | Dhizuku ou Shizuku installé | Débogage activé.

1. **Installer** : Téléchargez l'APK depuis les [Releases](https://github.com/mubdemub-code/ImperiumFramework/releases).
2. **Configurer** : Dans les paramètres, sélectionnez votre méthode (Dhizuku recommandé).
3. **Autoriser** : Validez la demande de privilèges Device Owner / ADB.

---

### 🛠 Stack Technique

| Composant | Technologie |
| :--- | :--- |
| **Langage** | Kotlin (Coroutines & Flow) |
| **UI Framework** | Jetpack Compose / Material 3 |
| **Privilèges** | Dhizuku API & Shizuku |
| **Core Engine** | LSPatch Core |
| **Architecture** | MVI / Clean Architecture |

---

### ⚠️ Sécurité
Imperium manipule des privilèges de bas niveau. L'utilisation des fonctions "Device Owner" relève de votre responsabilité. Le développeur n'est pas responsable des dommages matériels ou pertes de données.

---

### 🤝 Contribution & License
Les PR sont les bienvenues ! 
1. **Fork** → 2. **Branch** → 3. **Commit** → 4. **Pull Request**.

Distribué sous licence **GPL-3.0**. Développé avec passion par **MUB**.
