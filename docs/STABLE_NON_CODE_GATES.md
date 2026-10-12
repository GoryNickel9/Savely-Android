# Gate non di codice per la 1.0

La [qualifica successiva C01](COUPLE_INVITATION_QUALIFICATION.md) chiude localmente rifiuto e cancellazione degli inviti dai form QA: campione release API 31, 7/7 fixture su API 31/35, riletture per entrambi i ruoli e pulizia dei soli record del run. La matrice conserva 30 verifiche aperte. Gli altri percorsi Coppia/Admin, file, confronto e gate esterni restano da qualificare; le sezioni precedenti conservano i propri conteggi storici.

## Aggiornamento candidata locale 0.1.10-alpha

Il [rapporto firmato](SIGNED_RELEASE_QUALIFICATION.md) e la [matrice](STABLE_VERIFICATION_MATRIX.md) aggiungono campioni sul release R8 code11/hash `d6c4ccb0ab8b16c9fa893a4b119253aa4b3ff859249ccfb2dd344d15e12f0daa`, produzione `459bbac`. 536 JVM per variante e 170/170 debug API 35 passano; API 31 passa 170/170 in 551,778 s, senza failure/skip. V01 e R01 sono **PASS LOCALE**; le altre 31 righe rimangono aperte. La qualifica storica 164/164 API 31/35 sotto non viene trasferita al nuovo sorgente.

Sono osservati upgrade autenticato API 35 e preferenze/sessione, export locale API 31/35 di 11 categorie/1.696 byte, annulla e recovery esplicito dopo process death con nuovo documento vuoto rimosso. Il nuovo run API 31 passa login, upgrade, profilo, English/Light e logout; sessione server identica prima/dopo e zero finale. Export/BANK/FX restano nel precedente run separato, con logout UI/server zero manuale. Quattro tentativi driver falliti conservati. BANK API 31 conserva preview USD dopo process death, NORMALIZED è EUR, senza commit import. FX dal form nativo salva 0,01 USD/cambio EUR 0,894370 e DELETE UI lascia zero righe QA; Books risponde 429 e il percorso positivo resta aperto.

Conservati 81 PNG e 81 JSON per API; raccolta conclusa ed emulatori spenti, come confermato dal responsabile dell’integrazione.

Backup firma recuperato e usato per firmare un APK di test sullo stesso computer, tamper respinto e temporanei rimossi; recovery fuori computer resta aperta. Licenze runtime/font e asset finali sono inventariati, con normalizzazione NOTICE/MPL dichiarata; non è un parere legale. Le checkbox globali restano aperte quando il campione non copre l’intero criterio. Nessuna CI, pubblicazione o dichiarazione che restino solo ostacoli esterni.

## Evidenza storica precedente: 164 casi per API

La [matrice operativa successiva](STABLE_VERIFICATION_MATRIX.md) associa ogni area al codice e alla prova necessaria. La [verifica locale autorizzata](RECREATION_REGRESSION_VERIFICATION.md) qualifica SC01/SC02 e V01: 530 JVM, 36 host Python e due suite complete da 164/164 su API 31/35; lint senza errori, emulatori spenti. Le altre 32 righe e le checkbox reali di questo documento restano aperte: nessuna CI, nuova prova backend, telefono o APK di distribuzione finale deriva da questo run.

Checklist al 12 ottobre 2026, riferita alla richiesta di verifica del sorgente `75d30ed`. Non introduce funzionalità: distingue le prove ancora necessarie dagli ostacoli esterni. Le verifiche integrate sono gestite dal responsabile dell'integrazione; questo documento non ne anticipa gli esiti.

Fonti: [QA_QUALIFICATION.md](QA_QUALIFICATION.md), [FEATURE_PARITY.md](FEATURE_PARITY.md) e [ROADMAP.md](../ROADMAP.md). I risultati della 0.1.9-alpha e di `0714f39` restano associati a quei sorgenti e ai rispettivi APK. Non qualificano automaticamente la candidata finale. Nessuna data di rilascio è assegnata qui.

## 1. Confronto visivo e numerico sullo stesso dataset

- [x] Fissare un dataset sintetico comune, ruoli, lingua, valuta, data di riferimento e timezone. [Dataset master](../parity/fixtures/static-common-dataset.json) e [rapporto](COMMON_DATASET_PARITY_REVIEW.md): 50 casi di base con fonti/attesi, web `1cfcf6297e85d56bfafe3522362ceecef6f6b281`, Android `614bdbf`, produzione/JVM `935c7ba`. I profili sono fittizi. Questa preparazione non qualificava un APK; il campione osservato successivo è riportato nella voce seguente e non copre tutti i 50 casi.
- [ ] Confrontare schermate, azioni e valori nelle aree della matrice di parità: riepiloghi, filtri, form, grafici, dettagli e navigazione. Includere IT/EN, testo ingrandito, dati vuoti, permessi negati ed errori. Conservare screenshot e una tabella con input, atteso, osservato e scostamento per caso; associare l'APK osservato e, per il sito distribuito, la revisione del deploy. Il [campione successivo](COMMON_VISUAL_PARITY_REVIEW.md) acquisisce 81 PNG nativi e 114 web appaiati, con 28/28 test mirati API 31. Comprende dati popolati, IT/EN, testo grande e diniego Admin; ogni form, navigazione, dati vuoti ed errori non sono ancora coperti.
- [ ] Separare le differenze intenzionali dagli errori: importi decimali e valute distinte; storico ricostruito senza feed storico di mercato; copertura parziale dei prezzi registrati; snapshot patrimonio senza valuta. Non confrontare un totale multivaluta con una singola valuta o attribuire una valuta ai valori server. Il nuovo rapporto documenta queste differenze per Portafoglio, Patrimonio, TCG e Coppia e la diversa identificazione delle sessioni Security; non chiude tutta la matrice.

**Chiusura:** ogni caso richiesto ha un confronto tracciabile o una differenza motivata. Il codice letto e i test di una versione precedente non sostituiscono questo confronto.

## 2. Percorsi reali QA ancora esclusi

- [ ] Collegare le prove già disponibili ai soli comportamenti osservati: password/TOTP/revoca, Coppia con split EUR/USD e archivio, diniego Admin e modifica/ripristino del permesso QA. I report e i limiti sono in [QA_QUALIFICATION.md](QA_QUALIFICATION.md); non dimostrano ogni percorso delle schermate.
- [ ] Qualificare i percorsi esclusi usando identità e record dedicati: submit dei form reali, rifiuto/cancellazione inviti, altre rimozioni delle quote, revoca individuale delle sessioni e modifiche account. Provare l'eliminazione soltanto su un'identità creata per essere cancellata, conservando i tre QA riutilizzabili. Conservare esito per contratto, UUID delle sole righe create e conferma della pulizia; una pulizia riuscita non trasforma un flusso fallito in PASS.
- [ ] Verificare consegna, conferma email, recupero e callback PKCE solo con una mailbox controllata e destinatari autorizzati. **Ostacolo attuale:** gli account `example.invalid` non hanno mailbox e l'utente non dispone di una casella dedicata. Non sono prove eseguibili con le credenziali attuali; non inviare email a terzi per aggirare il limite.

**Chiusura:** elenco dei percorsi qualificati con report e scope, più decisione esplicita sui percorsi rimasti esclusi. Senza mailbox, i gate email restano non verificati.

## 3. Storage, provider e servizi esterni

- [ ] Qualificare import/export tramite picker di sistema, file locale e provider cloud; conservare il CSV d'ingresso, anteprima, mapping, duplicati attesi, righe effettive ed export risultante. Includere l'interoperabilità Bank2CSV nel browser e l'import del CSV prodotto. XLS/XLSX, restore globale e convertitore PDF nativo non sono requisiti del riferimento web.
- [ ] Verificare interruzione reale del processo, ripristino, cambio account e errore di scrittura sul disco nei flussi che conservano stato. Il registro import nel ViewModel non è un checkpoint su disco: la prova deve distinguere dati già salvati da lavoro interrotto e verificare la riconciliazione senza replay automatico.
- [ ] Qualificare i servizi esterni nel loro scope: ricerca Google Books, quotazioni CardTrader EUR e cambi nel flusso finanziario. Conservare endpoint, momento, stato HTTP e risultato atteso senza credenziali o contenuti sensibili. **Evidenza storica:** Google Books ha restituito HTTP 429; quel run non ha superato la ricerca reale. Un errore gestito correttamente e una ricerca riuscita sono due evidenze diverse.

**Chiusura:** prove dei percorsi sistema/provider e dei servizi richiesti, con indisponibilità dichiarate. Fixture e letture backend non certificano picker, provider cloud o disponibilità del fornitore.

## 4. APK finale, firma, aggiornamento e telefono

- [x] Associare gli esiti al sorgente finale e all’APK release ottimizzato code11/hash `d6c4ccb0…e12f0daa`: versione, certificato/firma e lint verificati; 536 JVM per variante e suite debug 170/170 API 31/35 sullo stesso app `459bbac`/fixture `74d7d0d`. Campione release R8 login/upgrade/export osservato separatamente. R01 PASS LOCALE nel suo criterio originale, senza attribuire i 170 test debug al release minificato o qualificare tutti i suoi flussi.
- [ ] Verificare aggiornamento con la stessa firma e una sessione autenticata, conservazione dei dati locali, ripristino della sessione e accesso ai dati dopo l'installazione. La prova storica 0.1.8→0.1.9 riguarda un AVD anonimo. La candidata corrente aggiunge upgrade autenticato API 35 e nuovo run completo API 31 con logout e pulizia finali verificati. Il backup è ora recuperato/usato sullo stesso computer; recupero su un altro computer resta distinto.
- [ ] Registrare separatamente la qualifica su telefono fisico. L'utente ha confermato installazione e uso generale della 0.1.7 sul Doogee S100 Android 12, ma ha escluso ulteriori prove dettagliate dei nuovi flussi sul proprio dispositivo. Quella conferma non qualifica i nuovi flussi né l'APK finale. Durante questa verifica sono disponibili soltanto AVD: il gate fisico resta aperto, senza attribuire al telefono risultati dell'emulatore.

**Chiusura:** evidenza dell'APK finale firmato e dell'aggiornamento autenticato, con stato esplicito della prova fisica. La sola produzione del file APK non basta.

## 5. Revisione editoriale e rilascio

- [x] Confronto testuale al riferimento fissato: identità, contatti, data 27 agosto 2026, ordine delle sezioni e link IT/EN preservati. Il generatore rieseguito in una cartella separata produce 12/12 file corrispondenti agli asset Android, con terminatori di riga normalizzati. Corretti i frammenti dei Termini e la frase Cookies EN. Questa evidenza non certifica validità giuridica o infrastruttura attuale.

- [ ] Confrontare integralmente privacy, cookie e termini IT/EN con il riferimento, verificando l'addendum Android su storage e servizi usati. Controllare testi, collegamenti, istruzioni di installazione/aggiornamento, limiti e licenze; non descrivere comportamenti cloud o garanzie non osservati.
- [ ] Consolidare matrice di parità, contratti e note di rilascio con evidenze per caso: fonte web, sorgente Android, ruolo, versione APK, atteso e osservato. Conservare anche i fallimenti e i recovery. Un run reale circoscritto, un audit statico o la sola corrispondenza delle SHA non dimostrano parità globale del backend distribuito.
- [ ] Preparare il pacchetto di rilascio con APK firmato, checksum, versione e limiti noti. Aggiornare baseline e dichiarazione di parità solo dopo la verifica del perimetro richiesto; eventuali esclusioni devono essere accettate esplicitamente e riportate nel rilascio. `globalParityVerified=false` e `verifiedApkVersion=null` non diventano veri o valorizzati per la sola presenza del codice.

**Chiusura:** documenti coerenti con le prove della candidata e decisione di rilascio sul perimetro realmente qualificato. Mailbox, telefono e disponibilità dei servizi restano ostacoli dichiarati finché non esiste l'evidenza necessaria.
