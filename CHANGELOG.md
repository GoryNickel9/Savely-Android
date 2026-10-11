# Changelog

## 0.1.0-alpha — 11 ottobre 2026

Prima app nativa Kotlin/Compose, con backend Savely condiviso, sessioni cifrate, permessi, finanza e moduli. Aggiunti import/export CSV con anteprima, testi legali nativi, persistenza FIRE per account, test dei contratti e fixture numeriche del web.

APK alpha firmato e ottimizzato; 171 test JVM, 26 test Android e lint debug/release superati. Nessuna modifica alla repo web o alla configurazione Supabase. Le differenze e le funzioni incomplete sono registrate nei contratti e nella baseline di parità.

Gli smoke Android aggiuntivi sul backend reale passano con l'account E2E esistente: sessione cifrata riaperta anche in un processo nuovo, dashboard, lettura del movimento dopo ricreazione, modifica ed eliminazione del solo dato di prova. Verificata la pulizia dopo un'interruzione host. Restano login con password, lettura dal browser web, prova su telefono, aggiornamenti con sessione reale e parità avanzata. Conferma email e reset usano il callback web; lingua dell'interfaccia solo italiana.

Ricorrenze con suggerimenti, esclusioni per account, pausa e elaborazione esplicita delle scadenze; Portafoglio con valore TCG e liquidità/illiquidità; filtri delle collezioni; confronto budget Coppia; audit accessi e revoca tramite session ID. Persistenza FIRE verificata sul dispositivo per tutti i dieci parametri, isolamento, bozze invalide e reset.
