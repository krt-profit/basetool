### Security

- **Server-Zugang.** Nach einer sudo-Anmeldung bekommen andere Sitzungen desselben Kontos nicht
  mehr zehn Minuten lang Root-Rechte. Die Prüfsuite fragt das sudo-Passwort einmal ab und gibt es
  pro Befehl verdeckt weiter (REQ-OPS-052).
