# Ciao Cla – regole del progetto

## Requisito fisso dell'utente
- L'app DEVE funzionare su tutti gli smartphone: ogni marca Android (Samsung, Xiaomi, Huawei/Honor,
  Oppo, Motorola, Pixel...) e, dove possibile, anche iPhone.
- Ogni modifica va pensata per la compatibilità più ampia: niente funzioni legate a una sola marca,
  sempre un piano B (es. motore vocale alternativo, inserimento manuale dei comandi).

## Note tecniche
- minSdk 24 (Android 7+), targetSdk 34. Logica dell'app in app/src/main/assets/index.html (WebView),
  parte nativa in Engine.kt / ListenService.kt / MainActivity.kt.
- Riconoscimento vocale: preferire Google, poi gli altri motori installati, poi il predefinito.
- L'APK si compila con GitHub Actions e si pubblica nella release "ultima".
- Firma fissa (app/ciaocla.keystore): non cambiarla, altrimenti gli aggiornamenti non si installano.
- Lingua dell'utente e dell'interfaccia: italiano.
