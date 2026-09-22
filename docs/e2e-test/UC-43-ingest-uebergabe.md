# UC-43 — Ingest-Übergabe aus dem Desktop-Extractor (Ein-Klick-Handoff)

|                |                                                                                                                                  |
|----------------|----------------------------------------------------------------------------------------------------------------------------------|
| **ID**         | UC-43                                                                                                                            |
| **Tag**        | `e2e`                                                                                                                            |
| **Testklasse** | [`IngestHandoffE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/IngestHandoffE2eTest.java)       |
| **Spec**       | [`desktop-ingest.md`](../specs/desktop-ingest.md) (REQ-INGEST-003/-004) · Basis-Flow [UC-24](UC-24-refinery-import-extract.md)   |

## Akteur

`test-admin` — öffnet den Übergabe-Link im Browser. Ein zweiter Nutzer ist nur Eigentümer eines fremden Handoffs.

## Vorbedingungen

Nur im ephemeren Modus (`assumeTrue(STACK.managesStack())`), weil der Test die Redis des Stacks und das Backend-Seeding braucht:

- Der Test postet das Fixture `refinery-extract-e2e.json` an `/api/v1/refinery-orders/import-extract` und erhält damit genau die Entwurfsantwort, die der echte Backend-Matcher aus den Namen im geseedeten Katalog erzeugt.
- Diese Antwort legt er unverändert in dieselbe Redis, die das Frontend liest, unter `ingest:handoff:<sub>:<id>` ab — die Redis der E2E-Compose-Datei (`redis-dev`), auf `127.0.0.1:6379` veröffentlicht, mit dem Wegwerf-Passwort aus `docker-compose.e2e.yml`.

## Auslöser

Der Extractor öffnet `/refinery-orders/create?handoff=<id>` im Browser des Nutzers.

## Hauptablauf

1. **Abholen:** `/refinery-orders/create?handoff=<id>` für einen Handoff des angemeldeten Nutzers öffnen.
2. **Wiederholen:** denselben Link ein zweites Mal öffnen.
3. **Fremder Handoff:** einen Link öffnen, dessen Eintrag für einen anderen Nutzer abgelegt ist; ebenso eine völlig unbekannte Id.

## Erwartetes Ergebnis

- Beim ersten Abholen erscheint das Import-Banner, und das Formular ist wie beim manuellen Upload aus [UC-24](UC-24-refinery-import-extract.md) vorbefüllt: Eingangsmaterial, Mengen (250 / 120), Qualität (618) und Startzeit; die nicht zugeordnete zweite Zeile bleibt leer. Danach hält Redis den Eintrag nicht mehr.
- Beim Wiederholen erscheint der freundliche Hinweis (`refinery-import-error`), kein Banner, keine Vorbefüllung — die Übergabe ist **einmalig**.
- Der fremde Handoff verhält sich wie eine unbekannte Id: Hinweis, kein Banner, keine Vorbefüllung — und der fremde Eintrag liegt **weiter** in Redis. Es gibt kein IDOR und keinen Weg, den Entwurf eines anderen zu verbrauchen.

## Sonderfälle & Lehren

- **Das Gateway läuft hier nicht.** Der Test stellt nach, was das Ingest-Gateway tut, und nutzt dafür den echten Matcher. So bleibt die Form des Entwurfs ehrlich — es ist genau das JSON, das die Produktion ablegt — ohne den Device-Grant aufzubauen. Den Gateway selbst decken die Tests des `ingest`-Moduls ab.
- **Schlüssel pro `sub`.** Weil der Schlüssel die Subject-Id enthält, sieht ein anderer Nutzer den Eintrag gar nicht erst; die Isolation hängt nicht an einer nachgelagerten Prüfung.
- **Aufbewahrung:** Ein abgelegter Handoff läuft nach `app.ingest.handoff-ttl` (Standard 30 Minuten) ab; dieser Test prüft die Ablaufzeit nicht.
