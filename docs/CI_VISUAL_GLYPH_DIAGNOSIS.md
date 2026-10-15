# Diagnosi CI — glifi USD e viewport sintetico

Il [diagnostico 38730540982](https://github.com/GoryNickel9/Savely-Android/actions/runs/38730540982), sorgente `2747631`, passa build/lint, **593 JVM** e **un test UI IT/EN su API 35**. Il controllo dei pixel conserva la soglia originale. Non è una nuova suite UI completa e non comprende le successive modifiche Poker locali.

## Causa osservata e correzione

Nel run `38728935988` la quarta riga USD è dipinta ma attenuata nella fascia inferiore della finestra: 25 caratteri non superano il controllo dell’inchiostro, mentre le altre tre righe lo superano. I PNG e le posizioni corrispondono alla fascia di navigazione; gli inset non erano registrati in quel run. Il wrapper sintetico occupava tutta la finestra API 35. La schermata di produzione gestisce già i propri inset.

Il commit `2747631` applica `WindowInsets.safeDrawing` al contenitore esterno della fixture. Tag e cattura appartengono al viewport interno. I metadati registrano finestra, inset e area sicura; quando disponibili, verificano che la cattura vi rientri. Nessuna modifica alla produzione o alla soglia dei glifi.

La nuova prova registra finestra 1080×1920, inset superiore 63 px e inferiore 126 px. Le catture misurano 945×1731 IT e 840×1731 EN: **360/320×659,43 dp**, rispetto agli 800 dp richiesti. Tutte le righe USD 25/20/5/0 sono complete e contrastate. Le due letture raster, prima e dopo due frame, passano già alla prima lettura. I contenuti EUR adiacenti possono essere fuori viewport.

## Prove conservate

| Run | Risultato e perimetro |
| --- | --- |
| `38580288372` | CI completa storica fallita: build 592/593, UI 229/230. Esito conservato. |
| `38586961412` | Build riuscita, diagnostico 0/1; raccolta priva di PNG/JSON, causa allora non identificabile. |
| `38728935988` | Diagnostico 0/1; raccolti sette file IT. EN non raggiunto. |
| `38730540982` | Build/lint e 593 JVM, diagnostico 1/1 IT/EN, 14 file e TAR verificati. |

Il runner diretto del solo scope `portfolio-glyph` raccoglie i 14 nomi sintetici ammessi prima del teardown. Richiede esattamente un test riuscito, conserva il fallimento e non raccoglie credenziali, preferenze, logcat o alberi UI. Lo scope `full` resta su `connectedDebugAndroidTest`.

Archivio locale: `artifacts/ci-glyph-review-38730540982/`. Revisionati tutti i sei PNG; il coordinatore ha controllato anche i due campioni finali IT/EN. Sei JSON geometrici e due JSON dei glifi completano le 14 catture; hash e dimensioni del TAR e dei due APK UI verificati. SHA-256 `review.json`: `9d1c99f8b168197e98b46f6ddc45325189113529b31572558032dec0228661e3`; TAR `66f78aaff07afbaac53ca46cc47b0c29fe2795506946d5de296699eccab956b8`.

Gli APK archiviati sono gli output del job UI; i byte installati non sono stati riletti indipendentemente. Questa prova non modifica i conteggi della matrice, i flag di parità o la release pubblica.
