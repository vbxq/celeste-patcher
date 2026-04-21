# PORTING.md — KettuManager → CelesteManager

## Structure du projet source (KettuManager)

KettuManager est une app Android (Kotlin, Jetpack Compose, Koin DI, Ktor HTTP, Voyager nav)
qui télécharge Discord depuis un mirror, injecte le module KettuXposed via LSPatch, et
installe l'APK patché.

## URLs à remplacer

| Emplacement | Original | Celeste |
|-------------|----------|---------|
| `DownloadVendettaStep.kt` | `https://github.com/C0C0B01/KettuXposed/releases/latest/download/app-release.apk` | `https://cdn.celeste.gg/mobile/xposed.apk` |
| `DownloadManager.kt:25` | `https://github.com/C0C0B01/KettuXposed/releases/latest/download/app-release.apk` | `https://cdn.celeste.gg/mobile/xposed.apk` |
| `DownloadManager.kt:32` | `https://github.com/C0C0B01/KettuManager/releases/latest/download/Manager.apk` | `https://cdn.celeste.gg/mobile/CelesteManager.apk` |
| `HomeViewModel.kt:119` | `C0C0B01/KettuManager` (GitHub API release check) | `celeste-gg/CelesteManager` |
| `HomeViewModel.kt:128` | `C0C0B01/KettuXposed` (module version check) | `celeste-gg/CelesteXposed` |
| `RestService.kt:17-19` | `api.github.com/repos/...` | Conservé tel quel |
| `AboutScreen.kt:137` | `https://github.com/C0C0B01/Kettu` | `https://celeste.gg` |
| `AboutScreen.kt:142` | `https://discord.gg/pkewGMtmYf` | `https://celeste.gg` |
| `PreferenceManager.kt:79-85` | Mirrors (vendetta.rocks, k6.tf, etc.) | Mirror unique : `https://alpha.celeste.gg` |
| `CommitsPagingSource.kt` | GitHub commits feed | `celeste-gg/Celeste` |

## Bundles JS (upload manuel)

Les fichiers suivants sont uploadés manuellement sur cdn.celeste.gg :
- `https://cdn.celeste.gg/mobile/celeste.js` — Bundle combiné (preload + kettu)
- `https://cdn.celeste.gg/mobile/celeste-preload.js` — Preload seul (redirection réseau)
- `https://cdn.celeste.gg/mobile/xposed.apk` — Module Xposed (KettuXposed vanilla ou custom)

CelesteManager télécharge le module Xposed depuis cdn.celeste.gg et l'injecte
dans Discord via LSPatch. Le module Xposed charge ensuite le bundle JS au runtime.

## Identité à remplacer

| Champ | Original | Celeste |
|-------|----------|---------|
| `applicationId` | `cocobo1.pupu.manager` | `gg.celeste.manager` |
| `namespace` | `dev.beefers.vendetta.manager` | `gg.celeste.manager` |
| Package Kotlin | `dev.beefers.vendetta.manager` | `gg.celeste.manager` |
| `app_name` (strings.xml) | "Kettu Manager" | "CelesteManager" |
| `packageName` (pref default) | `cocobo1.pupu.app` | `gg.celeste.app` |
| `appName` (pref default) | "Kettu" | "Celeste" |
| Download notif title | "Kettu Manager" | "CelesteManager" |
| About screen credits | Kettu/Bunny/Vendetta team | Celeste team |

## Fichiers modifiés

1. `app/build.gradle.kts` — applicationId, namespace
2. `app/src/main/AndroidManifest.xml` — package references
3. `app/src/main/res/values/strings.xml` — toutes les mentions Kettu
4. `app/src/main/java/.../utils/Constants.kt` — team members
5. `app/src/main/java/.../domain/manager/PreferenceManager.kt` — defaults, mirrors
6. `app/src/main/java/.../domain/manager/DownloadManager.kt` — URLs
7. `app/src/main/java/.../installer/step/download/DownloadVendettaStep.kt` — module URL
8. `app/src/main/java/.../ui/screen/about/AboutScreen.kt` — crédits, liens
9. `app/src/main/java/.../ui/viewmodel/home/HomeViewModel.kt` — update checks
10. `app/src/main/java/.../network/service/RestService.kt` — API endpoints
11. Tous les fichiers Kotlin (rename package)

## Logique d'injection (non modifiée)

Le flow d'installation est :
1. Télécharge les APKs Discord split (base, libs, lang, resources)
2. Télécharge le module Xposed (KettuXposed → notre version)
3. Signe les APKs avec une clé custom
4. Patche les manifests (change package name, app name, désactive les updates)
5. Injecte le module Xposed via LSPatch (embeds the .apk as a module)
6. Installe le résultat

Aucune vérification de hash/signature du bundle JS — le module Xposed est
embedé tel quel. Le bundle JS (kettu.js) est téléchargé par le module Xposed
lui-même au premier lancement via UpdaterModule, pas par le Manager.

## Point critique : configuration du bundle URL

Le Manager n'écrit PAS de loader.json. C'est le module Xposed (KettuXposed)
qui gère son propre téléchargement du bundle JS. Pour pointer vers notre bundle :

Option A : Modifier KettuXposed (DEFAULT_BASE_URL dans UpdaterModule.kt)
Option B : Écrire loader.json avec customLoadUrl dans le data dir de Discord

On utilise l'Option B : après installation, écrire le fichier loader.json.
Cela nécessite root ou un hook post-install.

MAIS : la solution plus propre est que notre module Xposed (celui qu'on embed)
soit déjà configuré avec notre URL par défaut. On embed notre propre build
de KettuXposed avec DEFAULT_BASE_URL pointant vers alpha.celeste.gg/mobile/.

Pour le MVP : on utilise le preload approach. Le module Xposed vanilla charge
le bundle depuis Codeberg, et notre preload (dans files/pyoncord/preloads/)
fait la redirection réseau. Le preload est copié par le Manager post-install.
