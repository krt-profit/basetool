### Security

- **Exchange-Gateway.** Das Gateway schickt Redis keine Lua-Skripte mehr: Byte-Budget,
  Schreibkontingente und Idempotenz-Sperren laufen als optimistische Transaktionen über einen
  Verbindungspool. Vor dem Release wird die Redis-ACL neu gerendert und geladen (ADR-0389).
