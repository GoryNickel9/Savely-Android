# Changelog

## 0.1.3-alpha — 11 ottobre 2026

Gli endpoint recupero password e Admin usano il dominio canonico www per evitare il redirect 308 rifiutato dal client. Callback signup e Origin restano configurati come prima. Insights aggiunge possibili spese ricorrenti, con massimo tre candidati e copertura dichiarata 4/8.

Passano 226 test JVM e 39 Android, inclusi retry MFA, filtri/esclusioni delle ricorrenze e salvataggio/modifica/reset dei derivati Fumo. Lint debug/release senza errori; APK firmato con versionCode 4 aggiornato sopra la 0.1.2 sul solo emulatore, senza account. Invio email reale, Admin reale, login con password, telefono e parità completa restano aperti.

## 0.1.2-alpha — 11 ottobre 2026

Una sessione revocata o illeggibile azzera subito account e permessi anche da una chiamata diretta delle impostazioni; errori temporanei conservano la sessione. Eventi del vecchio login e risposte profilo tardive non sovrascrivono il nuovo stato. Insights aggiunge il mese di risparmio, severità e filtri, con copertura dichiarata 3/8. Guadagno orario Poker corregge ±0,125 in ±0,13 e conserva gli altri arrotondamenti del riferimento web.

Passano 212 test JVM, 36 Android e lint debug/release senza errori. Ripetuti sincronizzazione Android→sito e ripristino in un processo nuovo sul solo account E2E, con pulizia completa. APK firmato/versionCode 3 aggiornato sopra la 0.1.1 sul solo emulatore, senza account. Restano login con password, telefono e parità completa.

## 0.1.1-alpha — 11 ottobre 2026

Budget con mediana mensile globale e riepiloghi separati per valuta; arrotondamento monetario `HALF_UP` e soglia di perdita del portafoglio confrontata prima di arrotondare. Manga con intervalli fino a 100 record, prezzi unitari, UUID stabili, riconciliazione delle risposte ambigue e ripresa esplicita della serie dopo ripristino dello stato della composizione.

Passano 193 test JVM, 32 test Android e lint debug/release senza errori. Verificati i filtri Libreria/TCG su fixture TLS e il flusso reale della stessa transazione da Android al sito, con eliminazione e pulizia complete. APK firmato con versionCode 2; aggiornamento sopra la 0.1.0 verificato sull'AVD senza account. Login con password, telefono, sessione reale durante aggiornamento e parità completa restano aperti. Nessuna modifica al web o alla configurazione backend.

## 0.1.0-alpha — 11 ottobre 2026

Prima app nativa Kotlin/Compose, con backend Savely condiviso, sessioni cifrate, permessi, finanza e moduli. Aggiunti import/export CSV con anteprima, testi legali nativi, persistenza FIRE per account, test dei contratti e fixture numeriche del web.

APK alpha firmato e ottimizzato; 171 test JVM, 26 test Android e lint debug/release superati. Nessuna modifica alla repo web o alla configurazione Supabase. Le differenze e le funzioni incomplete sono registrate nei contratti e nella baseline di parità.

Gli smoke Android aggiuntivi sul backend reale passano con l'account E2E esistente: sessione cifrata riaperta anche in un processo nuovo, dashboard, lettura del movimento dopo ricreazione, modifica ed eliminazione del solo dato di prova. Verificata la pulizia dopo un'interruzione host. Restano login con password, lettura dal browser web, prova su telefono, aggiornamenti con sessione reale e parità avanzata. Conferma email e reset usano il callback web; lingua dell'interfaccia solo italiana.

Ricorrenze con suggerimenti, esclusioni per account, pausa e elaborazione esplicita delle scadenze; Portafoglio con valore TCG e liquidità/illiquidità; filtri delle collezioni; confronto budget Coppia; audit accessi e revoca tramite session ID. Persistenza FIRE verificata sul dispositivo per tutti i dieci parametri, isolamento, bozze invalide e reset.
