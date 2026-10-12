# Licenze delle dipendenze Android

## Inventario del candidato

Il 12 ottobre 2026 sono state lette le dipendenze risolte di `releaseRuntimeClasspath`
da `artifacts/release-qualification/release-dependencies.txt`.
Il report contiene 110 coordinate senza contare BOM e constraint. Di queste,
80 hanno un JAR o AAR locale; le altre 30 sono metadati o selettori di piattaforma
senza artifact runtime locale. Le note distribuite elencano solo le 80 coordinate.
Gli artifact del compilatore, delle build e dei test restano fuori da questo inventario.

Per tutte le 80 coordinate è stata osservata una dichiarazione Apache License 2.0.
I POM di 97 dei 110 nodi erano nella cache Gradle. I 13 mancanti sono stati scaricati
da Google Maven o Maven Central; XML e coordinate sono stati validati con un limite
di 128 KiB per file e senza DTD. Per `com.google.guava:listenablefuture:1.0`
la dichiarazione proviene dal parent esplicito `com.google.guava:guava-parent:26.0-android`,
scaricato e verificato separatamente. Non resta un POM mancante in questo perimetro.

`artifacts/release-qualification/runtime-license-inventory.json` conserva coordinate,
percorsi e SHA-256 dei POM e degli artifact, URL dei progetti, licenze dichiarate,
URL dei download e notice incorporati. I POM scaricati e il testo MPL sono conservati
in `artifacts/release-qualification/license-metadata/`. Sono evidenze locali ignorate da Git.

## Famiglie runtime e versioni risolte

La lista completa delle coordinate è inclusa nelle
[note per l’app](../app/src/main/assets/licenses/third-party-notices.txt).

| Gruppo | Versione | Artifact locali | Licenza dichiarata |
|---|---|---|---|
| `androidx.activity` | 1.10.1 | `activity`, `activity-compose`, `activity-ktx` | Apache 2.0 |
| `androidx.annotation` | 1.4.1 | `annotation-experimental` | Apache 2.0 |
| `androidx.annotation` | 1.9.1 | `annotation-jvm` | Apache 2.0 |
| `androidx.appcompat` | 1.6.1 | `appcompat-resources` | Apache 2.0 |
| `androidx.arch.core` | 2.2.0 | `core-common`, `core-runtime` | Apache 2.0 |
| `androidx.autofill` | 1.0.0 | `autofill` | Apache 2.0 |
| `androidx.collection` | 1.5.0 | `collection-jvm`, `collection-ktx` | Apache 2.0 |
| `androidx.compose.animation` | 1.8.0 | `animation-android`, `animation-core-android` | Apache 2.0 |
| `androidx.compose.foundation` | 1.8.0 | `foundation-android`, `foundation-layout-android` | Apache 2.0 |
| `androidx.compose.material` | 1.7.8 | `material-icons-core-android`, `material-icons-extended-android` | Apache 2.0 |
| `androidx.compose.material` | 1.8.0 | `material-ripple-android` | Apache 2.0 |
| `androidx.compose.material3` | 1.3.2 | `material3-android` | Apache 2.0 |
| `androidx.compose.runtime` | 1.8.0 | `runtime-android`, `runtime-saveable-android` | Apache 2.0 |
| `androidx.compose.ui` | 1.8.0 | `ui-android`, `ui-geometry-android`, `ui-graphics-android`, `ui-text-android`, `ui-tooling-preview-android`, `ui-unit-android`, `ui-util-android` | Apache 2.0 |
| `androidx.concurrent` | 1.1.0 | `concurrent-futures` | Apache 2.0 |
| `androidx.core` | 1.0.0 | `core-viewtree` | Apache 2.0 |
| `androidx.core` | 1.16.0 | `core`, `core-ktx` | Apache 2.0 |
| `androidx.customview` | 1.0.0 | `customview-poolingcontainer` | Apache 2.0 |
| `androidx.emoji2` | 1.4.0 | `emoji2` | Apache 2.0 |
| `androidx.exifinterface` | 1.3.7 | `exifinterface` | Apache 2.0 |
| `androidx.graphics` | 1.0.1 | `graphics-path` | Apache 2.0 |
| `androidx.interpolator` | 1.0.0 | `interpolator` | Apache 2.0 |
| `androidx.lifecycle` | 2.9.0 | `lifecycle-common-java8`, `lifecycle-common-jvm`, `lifecycle-livedata-core`, `lifecycle-process`, `lifecycle-runtime-android`, `lifecycle-runtime-compose-android`, `lifecycle-runtime-ktx-android`, `lifecycle-viewmodel`, `lifecycle-viewmodel-android`, `lifecycle-viewmodel-compose-android`, `lifecycle-viewmodel-ktx`, `lifecycle-viewmodel-savedstate-android` | Apache 2.0 |
| `androidx.navigation` | 2.8.9 | `navigation-common`, `navigation-common-ktx`, `navigation-compose`, `navigation-runtime`, `navigation-runtime-ktx` | Apache 2.0 |
| `androidx.profileinstaller` | 1.4.0 | `profileinstaller` | Apache 2.0 |
| `androidx.savedstate` | 1.3.0 | `savedstate-android`, `savedstate-ktx` | Apache 2.0 |
| `androidx.startup` | 1.1.1 | `startup-runtime` | Apache 2.0 |
| `androidx.tracing` | 1.2.0 | `tracing` | Apache 2.0 |
| `androidx.vectordrawable` | 1.1.0 | `vectordrawable`, `vectordrawable-animated` | Apache 2.0 |
| `androidx.versionedparcelable` | 1.1.1 | `versionedparcelable` | Apache 2.0 |
| `com.caverock` | 1.4 | `androidsvg-aar` | Apache 2.0 |
| `com.google.accompanist` | 0.32.0 | `accompanist-drawablepainter` | Apache 2.0 |
| `com.google.guava` | 1.0 | `listenablefuture` | Apache 2.0 |
| `com.squareup.okhttp3` | 4.12.0 | `okhttp` | Apache 2.0 |
| `com.squareup.okio` | 3.9.0 | `okio-jvm` | Apache 2.0 |
| `io.coil-kt` | 2.7.0 | `coil`, `coil-base`, `coil-compose`, `coil-compose-base`, `coil-svg` | Apache 2.0 |
| `org.jetbrains` | 23.0.0 | `annotations` | Apache 2.0 |
| `org.jetbrains.kotlin` | 1.9.0 | `kotlin-stdlib-jdk7`, `kotlin-stdlib-jdk8` | Apache 2.0 |
| `org.jetbrains.kotlin` | 2.1.20 | `kotlin-stdlib` | Apache 2.0 |
| `org.jetbrains.kotlinx` | 1.10.2 | `kotlinx-coroutines-android`, `kotlinx-coroutines-core-jvm` | Apache 2.0 |
| `org.jetbrains.kotlinx` | 1.7.3 | `kotlinx-serialization-core-jvm` | Apache 2.0 |
| `org.jspecify` | 1.0.0 | `jspecify` | Apache 2.0 |

## Testi inclusi nell’app

- [Apache License 2.0](../app/src/main/assets/licenses/Apache-2.0.txt), copia senza modifiche del testo già incluso per Coil.
- [Note runtime e MPL 2.0](../app/src/main/assets/licenses/third-party-notices.txt), con il notice originale dei dati Public Suffix di OkHttp e il testo MPL completo.
- [Inter, SIL OFL 1.1](../app/src/main/assets/font_licenses/inter_ofl.txt) e [Space Grotesk, SIL OFL 1.1](../app/src/main/assets/font_licenses/space_grotesk_ofl.txt), già presenti con le attribuzioni originali.
- Copie originali per [Coil](../app/src/main/assets/third_party_licenses/coil_2.7.0_LICENSE.txt) e [AndroidSVG](../app/src/main/assets/third_party_licenses/androidsvg_1.4_LICENSE.txt), già presenti.

## Dati Public Suffix di OkHttp

Il POM di OkHttp 4.12.0 dichiara Apache 2.0 per la libreria. Il JAR contiene anche
`okhttp3/internal/publicsuffix/NOTICE`, che attribuisce `publicsuffixes.gz` alla Public Suffix List
e indica Mozilla Public License 2.0. Le note dell’app conservano quel notice senza modifiche.
Il testo completo è stato scaricato dal [sito Mozilla](https://www.mozilla.org/media/MPL/2.0/index.txt)
con un limite di 128 KiB. L’URL [della sorgente upstream](https://publicsuffix.org/list/public_suffix_list.dat)
è quello indicato dal notice; non è stata ricostruita la revisione storica esatta incorporata nel JAR.

## Limiti della verifica

Il conteggio segue le dipendenze risolte e gli artifact locali, non le classi rimaste
dopo R8. La dichiarazione di un POM non dimostra la copertura di ogni sorgente
incorporato: questo è un inventario tecnico, non un parere di conformità legale.
La raccolta non ha eseguito build o test Android. Il controllo che le nuove note
siano presenti nell’APK finale appartiene alla qualifica della release.
Font e copie di licenze esistenti sono stati conservati. Non sono stati modificati
i sorgenti delle librerie né scaricati nuovi artifact eseguibili.
