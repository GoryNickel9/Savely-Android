# Gate non di codice per la 1.0

Checklist al 12 ottobre 2026, riferita alla richiesta di verifica del sorgente `75d30ed`. Non introduce funzionalità: distingue le prove ancora necessarie dagli ostacoli esterni. Le verifiche integrate sono gestite dal responsabile dell'integrazione; questo documento non ne anticipa gli esiti.

Fonti: [QA_QUALIFICATION.md](QA_QUALIFICATION.md), [FEATURE_PARITY.md](FEATURE_PARITY.md) e [ROADMAP.md](../ROADMAP.md). I risultati della 0.1.9-alpha e di `0714f39` restano associati a quei sorgenti e ai rispettivi APK. Non qualificano automaticamente la candidata finale. Nessuna data di rilascio è assegnata qui.

## 1. Confronto visivo e numerico sullo stesso dataset

- [x] Fissare un dataset sintetico comune, ruoli, lingua, valuta, data di riferimento e timezone. [Dataset master](../parity/fixtures/static-common-dataset.json) e [rapporto](COMMON_DATASET_PARITY_REVIEW.md): 50 casi di base con fonti/attesi, web `1cfcf6297e85d56bfafe3522362ceecef6f6b281`, Android `614bdbf`, produzione/JVM `935c7ba`. I profili sono fittizi; nessuna qualifica osservata del dataset o APK associato a questi casi.
- [ ] Confrontare schermate, azioni e valori nelle aree della matrice di parità: riepiloghi, filtri, form, grafici, dettagli e navigazione. Includere IT/EN, testo ingrandito, dati vuoti, permessi negati ed errori. Conservare screenshot e una tabella con input, atteso, osservato e scostamento per caso; associare l'APK osservato e, per il sito distribuito, la revisione del deploy. Tre screenshot precedenti non coprono tutte le pagine.
- [ ] Separare le differenze intenzionali dagli errori: importi decimali e valute distinte; storico ricostruito senza feed storico di mercato; copertura parziale dei prezzi registrati; snapshot patrimonio senza valuta. Non confrontare un totale multivaluta con una singola valuta o attribuire una valuta ai valori server.

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

- [ ] Associare gli esiti integrati al sorgente finale e all'APK release ottimizzato: versione, checksum, certificato di firma e report delle verifiche richieste su Android 12/API 31 e Android 15/API 35. Esiti debug e vecchi APK restano evidenze separate.
- [ ] Verificare aggiornamento con la stessa firma e una sessione autenticata, conservazione dei dati locali, ripristino della sessione e accesso ai dati dopo l'installazione. La prova precedente 0.1.8→0.1.9 riguarda un AVD anonimo. Il backup cifrato della firma è documentato; il suo recupero su un altro computer resta una verifica distinta.
- [ ] Registrare separatamente la qualifica su telefono fisico. L'utente ha confermato installazione e uso generale della 0.1.7 sul Doogee S100 Android 12, ma ha escluso ulteriori prove dettagliate dei nuovi flussi sul proprio dispositivo. Quella conferma non qualifica i nuovi flussi né l'APK finale. Durante questa verifica sono disponibili soltanto AVD: il gate fisico resta aperto, senza attribuire al telefono risultati dell'emulatore.

**Chiusura:** evidenza dell'APK finale firmato e dell'aggiornamento autenticato, con stato esplicito della prova fisica. La sola produzione del file APK non basta.

## 5. Revisione editoriale e rilascio

- [x] Confronto testuale al riferimento fissato: identità, contatti, data 27 agosto 2026, ordine delle sezioni e link IT/EN preservati. Il generatore rieseguito in una cartella separata produce 12/12 file corrispondenti agli asset Android, con terminatori di riga normalizzati. Corretti i frammenti dei Termini e la frase Cookies EN. Questa evidenza non certifica validità giuridica o infrastruttura attuale.

- [ ] Confrontare integralmente privacy, cookie e termini IT/EN con il riferimento, verificando l'addendum Android su storage e servizi usati. Controllare testi, collegamenti, istruzioni di installazione/aggiornamento, limiti e licenze; non descrivere comportamenti cloud o garanzie non osservati.
- [ ] Consolidare matrice di parità, contratti e note di rilascio con evidenze per caso: fonte web, sorgente Android, ruolo, versione APK, atteso e osservato. Conservare anche i fallimenti e i recovery. Un run reale circoscritto, un audit statico o la sola corrispondenza delle SHA non dimostrano parità globale del backend distribuito.
- [ ] Preparare il pacchetto di rilascio con APK firmato, checksum, versione e limiti noti. Aggiornare baseline e dichiarazione di parità solo dopo la verifica del perimetro richiesto; eventuali esclusioni devono essere accettate esplicitamente e riportate nel rilascio. `globalParityVerified=false` e `verifiedApkVersion=null` non diventano veri o valorizzati per la sola presenza del codice.

**Chiusura:** documenti coerenti con le prove della candidata e decisione di rilascio sul perimetro realmente qualificato. Mailbox, telefono e disponibilità dei servizi restano ostacoli dichiarati finché non esiste l'evidenza necessaria.
