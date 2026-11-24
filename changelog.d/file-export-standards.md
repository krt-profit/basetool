### Changed

- **PDF-Exporte.** Kontoauszüge, 3-Monats-Report, Audit-Logs, Übergabeprotokolle und die
  Datenauskunft sind jetzt archivtaugliche PDF/A-4-Dateien (PDF 2.0). Der PDF-Betrachter zeigt
  den Dokumenttitel statt des Dateinamens, und alle Schriften sind eingebettet.
- **Ankauf-Bericht als CSV.** Die Datei trennt Felder jetzt nach RFC 4180 mit Komma statt
  Semikolon und beendet Zeilen mit CRLF. Beim Import in eine Tabellenkalkulation das Komma als
  Trennzeichen wählen.
- **Downloads.** Alle Dateidownloads melden sich dem Browser als normaler Anhang
  (`Content-Disposition: attachment`).
