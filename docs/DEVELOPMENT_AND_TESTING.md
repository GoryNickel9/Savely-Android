# Sviluppo, test e processi visibili

Data delle fonti consultate: 11 ottobre 2026. Questo documento descrive strumenti e procedura di lavoro; non costituisce un'autorizzazione di PokerStars.

## Cosa compare nel Gestore attività Windows

Durante la sola scrittura dei file restano visibili Codex, il terminale ed eventuali strumenti aperti. Il nome della repo non crea un processo applicativo. I nomi effettivi dipendono dalle versioni installate.

| Attività | Processi tipici sul PC |
|---|---|
| Scrittura con Codex/terminale | Codex, `powershell.exe` o `pwsh.exe`, eventuale `git.exe` |
| Android Studio aperto | `studio64.exe` e processi Java associati |
| Build Gradle/Kotlin e test JVM | `java.exe`, daemon Gradle/Kotlin ed eventuali worker |
| Test su emulatore | `emulator.exe`, `qemu-system-x86_64.exe`, `adb.exe`, oltre agli strumenti di build |
| Installazione/debug su telefono collegato | `adb.exe` e strumenti di sviluppo ancora aperti |
| APK usato autonomamente sul telefono | Savely gira su Android; nessun processo Windows Savely generato dall'APK |
| Build su GitHub Actions | Processi di compilazione sul runner GitHub, non sul PC locale |

Il Gestore attività mostra applicazioni, processi e risorse; può esporre ulteriori dettagli nelle colonne disponibili. Non mostra una valutazione "app consentita da PokerStars". [Documentazione Microsoft](https://learn.microsoft.com/en-us/troubleshoot/windows-server/support-tools/support-tools-task-manager).

Gradle e Kotlin possono mantenere daemon dopo la build; ADB mantiene un server sul computer. Prima di riprendere altre attività, chiudere gli strumenti di sviluppo e terminare normalmente le sessioni di test. Non terminare indiscriminatamente tutti i processi Java, perché possono appartenere ad altre applicazioni. [Gradle Daemon](https://docs.gradle.org/current/userguide/gradle_daemon.html), [Kotlin daemon](https://kotlinlang.org/docs/gradle-compilation-and-caches.html), [ADB](https://developer.android.com/tools/adb), [Android Emulator](https://developer.android.com/studio/run/emulator-commandline).

Durante questa attività sono stati eseguiti soltanto letture, ricerche, operazioni Git e scrittura di documentazione. Non sono stati avviati Android Studio, build Gradle, ADB o emulatori.

## Cosa dichiarano le fonti PokerStars

PokerStars IT dichiara di verificare la presenza di strumenti vietati in esecuzione e il comportamento di gioco. Proibisce assistenza decisionale in tempo reale e alcune analisi avanzate mentre il client è aperto. La pagina pubblica non dà un'approvazione specifica di Savely o dei nostri strumenti di sviluppo. [Policy PokerStars IT](https://www.pokerstars.it/poker/room/policy-di-utilizzo).

L'accordo di licenza IT, sezione 5.5, descrive l'esame dei programmi attivi contemporaneamente al software. Non pubblica le firme di rilevamento o criteri sufficienti per garantire come venga trattata questa app. [Accordo di licenza IT](https://www.pokerstars.it/content/dam/new-dam/italy/website/documenti/termini/Accordo%20di%20licenza%20con%20l%E2%80%99utente%20finale%20per%20il%20software%20online.pdf).

Dal codice Savely esaminato risultano moduli di contabilità poker: NextCut, guadagno orario, NetWon/EV e rakeback. Le pagine verificate non implementano un solver di equity/GTO, un HUD o lettura del client. La presenza della parola Poker non dimostra un divieto; nemmeno l'uso di strumenti di programmazione generici garantisce assenza di segnalazioni.

Non è possibile promettere "nessun rischio di ban" basandosi sui nomi dei processi. La tabella Windows descrive ciò che gira localmente, non gli esatti controlli interni di PokerStars.

## Procedura prevista per il progetto

1. Eseguire sviluppo, build e test con il client PokerStars completamente chiuso, non soltanto senza tavoli aperti. Non avviare, interrogare o controllare il suo processo dal codice Savely.
2. Preferire build e test automatici nel runner GitHub Android; usare emulatore e telefono per le verifiche locali richieste, sempre fuori dalla sessione di gioco.
3. Usare account/dati di test Savely. Non leggere finestre, carte, hand history live o input di gioco; nessuna automazione di puntate o interazione con il client.
4. Al termine chiudere normalmente emulatori, debugger e strumenti del progetto; verificare solo i processi che abbiamo avviato, senza occultarli o rinominarli.
5. Se si desidera usare strumenti di sviluppo contemporaneamente al client, chiedere una conferma scritta all'assistenza descrivendo le funzioni effettive. Non inviare messaggi all'assistenza senza autorizzazione dell'utente.

Questa procedura separa sviluppo e gioco; non è una certificazione dell'operatore. L'uso su un telefono o di un runner remoto non autorizza strumenti vietati durante il gioco.
