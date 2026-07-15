# NetWatch – Netzwerk-Diagnose für Android

Kleine Android-App, die den Netzwerkzustand des Handys analysiert und
Verbindungsabbrüche protokolliert. Ohne externe Abhängigkeiten, keine
Datenübertragung an Dritte – das Protokoll bleibt auf dem Gerät.

## Funktionen

- **Status-Anzeige:** Verbindungsart (WLAN/Mobilfunk), Internet-Erreichbarkeit,
  geschätzte Bandbreite, WLAN-Name und Signalstärke, IP-Adressen, DNS-Server.
- **Hintergrund-Überwachung:** Ein Vordergrund-Dienst protokolliert jeden
  Verbindungsabbruch mit Zeitstempel und Ausfalldauer, erkennt Wechsel
  zwischen WLAN und Mobilfunk und testet alle 30 Sekunden die tatsächliche
  Internet-Erreichbarkeit (Latenz-Messung gegen `connectivitycheck.gstatic.com`).
- **Ereignis-Protokoll:** Einsehbar in der App, teilbar per Share-Sheet
  (z.B. an Claude schicken zur Auswertung).

## Berechtigungen

- Standort (optional): nur nötig, damit Android den WLAN-Namen und die
  Signalstärke herausgibt. Ohne Freigabe funktioniert alles andere trotzdem.
- Benachrichtigungen: für die dauerhafte Anzeige während der Überwachung.

## Bauen

```
cd netwatch
./gradlew assembleDebug
```

Die APK liegt danach unter `app/build/outputs/apk/debug/app-debug.apk`.
Der GitHub-Actions-Workflow `.github/workflows/netwatch-apk.yml` baut die APK
automatisch und hängt sie an ein Release an.
