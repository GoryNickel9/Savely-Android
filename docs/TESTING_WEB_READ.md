# Lettura del marker QA sul sito reale

Lo smoke usa `https://www.savely.cc/transactions`, Edge headless e un nuovo contesto Playwright. Verifica la lettura di una sola riga creata dal processo host nel progetto `crqnfbahytzenisospcx`, con l'account QA esistente `9daffd41-39bf-4b1e-be68-abf12549c4a7` / `e2e-savely@example.com`. Lo scope del report è `host-created-row/web-read`: la prova non copre login con password, creazione dalla UI Android o SHA del deploy web.

## Avvio esplicito

```powershell
./tools/run-web-read-smoke.ps1 -RealWebRead
```

Richiede Node, CLI Supabase già autenticata sul progetto autorizzato, Edge installato e Playwright presente in `../Savely/node_modules`. Non installa dipendenze, non compila il web e non cambia file della repo web. Sintassi PowerShell e JavaScript, self-test locale e prova sul sito reale sono passati; risultato e limiti sono riportati sotto.

```powershell
node ./tools/web-read-smoke.mjs --self-test
```

Il self-test non legge credenziali, non apre browser e non chiama il backend. Verifica tutti i campi di ownership, la conservazione di righe modificate, POST/DELETE con risposta persa senza retry e la verifica di assenza tramite UUID/proprietario.

## Sequenza e guardie

1. L'host controlla progetto, chiave anon/pubblicabile e identità confermata dell'account QA senza fattori MFA verificati. Genera e verifica un magic link senza inviare email o cambiare la password. La chiave amministrativa resta nel processo PowerShell.
2. Node riceve la sola sessione QA via stdin, verifica metadati JWT e identità reale con `GET /auth/v1/user`, quindi controlla che l'UUID della riga non esista. Registra i metadati di cleanup prima dell'unica POST: `expense`, EUR 0,01, data odierna in Europe/Rome, categoria nulla, cambio EUR 1 e descrizione `Android-Web-QA-<UUID>`. Prima di giudicare la creazione esegue un GET per UUID/proprietario e controlla tutto il contenuto, anche se la risposta POST è stata persa.
3. Playwright avvia un processo Edge con profilo temporaneo e contesto vuoto. La sessione viene fornita in memoria nel localStorage della sola origine `https://www.savely.cc`, sotto `sb-crqnfbahytzenisospcx-auth-token`. Non apre o riusa browser, profili, cookie o connessioni CDP dell'utente.
4. Il browser apre la pagina reale, cerca il marker e richiede una sola riga con importo EUR 0,01. Tutte le scritture browser sono bloccate, compresi gli audit automatici `login_activity`; il conteggio viene dichiarato nel report. Richieste ad altre origini e WebSocket vengono bloccati. Nessun JWT viene inoltrato a un'origine diversa dal progetto previsto. Non viene cliccato «Esci», perché il web usa logout globale.
5. Il `finally` chiude browser e contesto e legge la riga per UUID/proprietario. Rifiuta la cancellazione se marker, importo, valuta, data, tipo, categoria nulla o cambio EUR 1 sono cambiati. L'unica DELETE usa predicati su tutti questi campi; il GET finale verifica zero righe per UUID/proprietario, così una modifica concorrente non può apparire come cancellazione riuscita. Poi revoca soltanto la sessione temporanea con `scope=local`. Un timeout della POST o DELETE non causa una ripetizione della scrittura.

## Risultati e interruzioni

`artifacts/web-read-smoke-result.json` contiene UUID, marker, origine effettiva, stato della lettura, richieste bloccate, rimozione e logout. `artifacts/web-read-smoke-row-<UUID>.png` mostra soltanto la riga filtrata di quella esecuzione. Il report non contiene token, chiavi o corpi di autenticazione. Non vengono salvati storageState, trace, HAR o video.

Un sito irraggiungibile, un blocco di accesso, un'origine differente o un selettore non corrispondente producono `stage=failed`; lo script non ripiega sul build locale e non dichiara il sito offline sulla sola base di un tool esterno. La cleanup della riga viene tentata anche dopo un errore del browser. Se non può verificarla, conserva `cleanup_pending=true` e gli identificatori esatti. Un arresto del processo Node dopo la POST può impedire il suo `finally`: il report scritto prima della creazione permette di riconciliare solo questo UUID/marker. L'host tenta comunque il logout della propria sessione; non esegue cancellazioni generiche o retry ciechi.

I file temporanei della sessione non vengono creati. Le credenziali rimangono in memoria fino alla chiusura dei processi. Screenshot e report non dimostrano quale commit sia attualmente distribuito sul sito.

## Prova eseguita — 11 ottobre 2026

Il preflight senza credenziali ha osservato HTTP 308 da `https://savely.cc/transactions` a `https://www.savely.cc/transactions`; l'origine canonica risponde HTTP 200. Lo script usa direttamente quest'ultima e rifiuta altre origini.

La prova reale passa con run UUID `7464b34d-ec2a-44d2-bd24-a297f2ab92e4`: una sola riga con marker e importo −€0,01 visibile nella pagina Transazioni. Lo screenshot della sola riga è stato controllato. Il report registra `browser_read_verified=true`, `transaction_removed=true`, `logout_server=true`, `cleanup_pending=false`; una richiesta audit e una richiesta esterna bloccate, zero scritture browser o inoltri JWT. La repo web resta invariata.

Il self-test locale passa per guardie sul contenuto completo, risposta POST/DELETE persa, conservazione delle righe modificate e verifica di assenza tramite UUID/proprietario. La prova browser usa una riga creata dall'host; il CRUD nativo verificato separatamente usa un altro marker. Non è un unico flusso Android→browser né una verifica del login con password.
