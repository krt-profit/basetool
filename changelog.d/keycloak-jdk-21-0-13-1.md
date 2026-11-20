### Security

- **Anmeldedienst.** Keycloak läuft auf dem neu gebauten Image 26.8.2-3 mit OpenJDK 21.0.13.1, das die
  Java-Lücke CVE-2026-114938 („Java4Shell“) schließt. Keycloak startet beim nächsten Deploy einmal neu.
