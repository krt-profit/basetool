### Security

- **Laufzeit, Java4Shell (CVE-2026-114938).** Backend, Frontend und Ingest laufen auf Temurin 27.0.1.1, Keycloak auf
  Red Hat OpenJDK 21.0.13.1; beide schließen die Lücke und die übrigen CVEs des OpenJDK-Advisorys vom 17.11. Der
  Übergangsschalter `-XX:-UseBASE64Intrinsics` entfällt damit.
