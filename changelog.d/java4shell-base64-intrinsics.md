### Security

- **Laufzeit, Java4Shell (CVE-2026-114938).** Backend, Frontend, Ingest und Keycloak starten mit abgeschaltetem
  hardwarebeschleunigtem Base64-Decoder (`-XX:-UseBASE64Intrinsics`). Das schließt die Pre-Auth-Lücke auf
  AVX-512-Hosts, bis gepatchte JDK-Images erscheinen.
