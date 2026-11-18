### Fixed

- **Überwachung.** Die IPv6-Prüfungen der Website, von Grafana und vom Ingest melden unter Podman 6
  keinen Ausfall mehr, obwohl die Seiten über IPv6 erreichbar sind. Sie erreichen den Server jetzt
  über seine eigene öffentliche IPv6-Adresse.
