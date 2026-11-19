### Security

- **Webserver.** Der vorgeschaltete nginx-Webserver läuft auf 1.31.9 und schließt damit zehn Lücken.
  Drei davon betreffen unsere Konfiguration: Sie erlaubten es, den Server über HTTP/2 oder TLS-Anfragen
  ohne Anmeldung lahmzulegen. Für Mitglieder ändert sich sonst nichts.
