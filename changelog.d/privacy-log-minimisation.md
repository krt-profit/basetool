### Security

- **Datenschutz bei Protokollen.** Das Zugriffsprotokoll des Servers speichert Adressen und Referrer
  ohne Abfrageparameter, Suchbegriffe wie Namen aus der Personensuche landen dort nicht mehr. Die
  Anwendung schreibt keine IP-Adressen mehr in ihre Protokolle, und Traces enthalten auch in der
  vollständigen Adresse keine Kennungen mehr.
- **Aufbewahrung.** Alle Protokolldateien auf dem Server werden höchstens 31 Tage aufbewahrt: Die
  Fehlerprotokolle der Anwendung 30 statt 60 Tage, die Systemprotokolle mit ihrer Kopie des
  Zugriffsprotokolls 28 Tage statt bis zu fünf Wochen.
