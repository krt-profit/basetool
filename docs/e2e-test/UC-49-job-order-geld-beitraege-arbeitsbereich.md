# UC-49 — Job Order: Geld- und Materialbeiträge im gemeinsamen Arbeitsbereich

|                |        |
|----------------|--------|
| **ID**         | UC-49  |
| **Tag**        | `e2e`  |
| **Testklasse** | [`JobOrderMoneyE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/JobOrderMoneyE2eTest.java) · [`JobOrderBankMoneyE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/JobOrderBankMoneyE2eTest.java) · [`JobOrderSharedWorkspaceE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/JobOrderSharedWorkspaceE2eTest.java) · [`MaterialContributionE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/MaterialContributionE2eTest.java) |
| **Basis-Flow** | [UC-03](UC-03-job-order-anlegen.md), [UC-06](UC-06-job-order-handover.md) · Spec [`orders-core.md`](../specs/orders-core.md) · [`orders-auec.md`](../specs/orders-auec.md) (REQ-ORDERS-057…065) · [`orders-auec-bank.md`](../specs/orders-auec-bank.md) (REQ-ORDERS-069…076) · [`orders-material-claims.md`](../specs/orders-material-claims.md) (REQ-ORDERS-024) |

## Akteur

Getrennte Konten je Rolle im Auftrag, jedes in einem eigenen Browser-Kontext:

- **Geld ohne Bank:** `test-order-writer` (verantwortlicher Schreiber), `test-order-reader` (Leser mit offenem Entwurf), `test-member` und `test-admin`.
- **Geld über die Bank:** der Schreiber und die Bank-Konten `test-bank-employee`, `test-bank-management`, `test-bank-member`.
- **Arbeitsbereich:** `test-member` als Besteller, `test-officer` als Bearbeiter.
- **Materialbeiträge:** `test-officer` als Beitragender, `test-admin` als Peer.

## Vorbedingungen

Alle vier Klassen brauchen den ephemeren Stack (`assumeTrue(STACK.managesStack())`). Per REST geseedet: frische Staffeln, die in den Profit-Bereich verschoben werden (`moveOrgUnitToProfit`), die Mitgliedschaften und Leitungsposten der Konten; für die Bank zusätzlich Einheiten-Bankkonten, Bank-Rechte, Kontoinhaber und Einzahlungen; für die Materialbeiträge eine Location, eine SK, ein Material-Auftrag, eine Zusage und ein Lagereintrag.

## Auslöser

Mehrere Konten arbeiten gleichzeitig am selben Auftrag: einer stellt Geld oder Material bereit, übergibt, korrigiert oder storniert, während andere den Auftrag mit offenem Entwurf ansehen.

## Hauptablauf

### Geldkonditionen ohne Bank (`JobOrderMoneyE2eTest`)

1. Einen aUEC-Auftrag über das native Formular `/orders/create` mit gewählter Einheit und Zielbetrag anlegen.
2. Eine Bereitstellung ohne Bank buchen, die Antwort verlieren und die Seite neu laden.
3. Für einen Inhaber ohne Konto bereitstellen, während ein Leser einen offenen Entwurf hält; ein bestehendes Konto ausdrücklich verknüpfen.
4. Einen abgelehnten, veralteten Entwurf nach Klärung seines unsicheren Vorgangs mit frischen Konditionen buchen.
5. Getrennte Bereitstellungen einsammeln und nach verlorener Antwort einer Teilübergabe bis zum Ziel übergeben; eine Übergabe durch einen kontextuellen Schreiber ohne Bank und ohne zentrale Sammlung abschließen.
6. Einen Einheitenbeitrag gutschreiben, gehaltenes Geld einem anderen Auftrag zuordnen und eine Bereitstellung samt abhängiger Sammlung über eine einzige Verlaufsaktion korrigieren.

### Geld über die Bank (`JobOrderBankMoneyE2eTest`, REQ-ORDERS-069…076)

7. Zwei echte Einzahlungen, vorhandenes Guthaben und Überweisungen des Bestellers über Kontopositionen gutschreiben und zurückgeben; eine falsche Buchungsanfrage lehnt die Bank auf `/bank/requests` ab.
8. Eine Auszahlung an einen Empfänger ohne Konto über das Quellkonto und dessen zahlenden Verwalter abrechnen.

### Gemeinsamer Arbeitsbereich (`JobOrderSharedWorkspaceE2eTest`)

9. Besteller und Bearbeiter öffnen denselben Auftrag; einer ändert Bearbeiter und Status, während der andere einen Notizentwurf offen hat.
10. Der Besteller storniert den Auftrag und öffnet ihn wieder; der Peer übergibt danach ohne Reload.

### Materialbeiträge (`MaterialContributionE2eTest`, REQ-ORDERS-024)

11. Lagerbestand auf `/inventory?storage=all` dem Auftrag zuordnen, während ein Peer `/orders/{id}?tab=materials` offen hat.
12. Die ursprüngliche Qualität einer Bereitstellung einmal korrigieren und die Qualität eines gewählten Teils des aktuellen Bestands ändern.
13. Einen Versionskonflikt am Bestand, eine verspätete Antwort auf eine Qualitätsabfrage und eine gescheiterte Pflichtaktualisierung herbeiführen.

## Erwartetes Ergebnis

- **Geld ohne Bank:** Das Formular sendet die kanonische Zieleinheit und leitet weiter; nach Antwortverlust liest die Seite genau das festgeschriebene Ergebnis zurück, ohne doppelt zu buchen. Ein offener Entwurf eines anderen Kontos bleibt bei jeder Peer-Änderung stehen; der Auftrag erscheint erst nach der Wahl eines echten Kontos in dessen offener Warteschlange. Korrekturen schreiben den ursprünglichen Verlauf nicht um, und eine Neuzuordnung aktualisiert beide Auftragsansichten.
- **Geld über die Bank:** Gutschrift, Rückgabe und Abrechnung laufen über Kontopositionen ohne Auftragsinhaber; die gemeldete Auszahlung steht beim Quellkonto und seinem Verwalter.
- **Arbeitsbereich:** Jede Änderung erreicht den anderen Kontext in place; Notizen tragen unabhängige Versionen, gemerkte Filter bleiben, und der offene Notizentwurf geht nie verloren.
- **Materialbeiträge:** Die Zuordnung schreibt genau einmal gut; eine Korrektur gegen einen veralteten Peer-Stand verlangt einen ausdrücklichen neuen Versuch. Qualitätskorrekturen ändern weder den übrigen Bestand noch die Gutschrift, und Konflikte, verspätete Antworten und gescheiterte Aktualisierungen behalten Auswahl und Korrektur bis zum ausdrücklichen Speichern.

## Sonderfälle & Lehren

- **Eigene Konten je Rolle.** Getrennte Konten für Schreiber, Leser, Besteller und Bank zeigen, dass die Live-Aktualisierung über Sitzungen verschiedener Nutzer läuft und jede Sicht ihre eigenen Rechte behält.
- **Antwortverlust wird simuliert, nicht abgewartet.** Die Tests verwerfen die Antwort eines festgeschriebenen Schreibvorgangs und prüfen, dass die Seite den Ausgang beim Backend nachliest, statt erneut zu senden.
