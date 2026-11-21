# UC-52 — Bedarf & Spenden: Bedarfe, Zusagen, Empfang & Statistik

|                |        |
|----------------|--------|
| **ID**         | UC-52  |
| **Tag**        | `e2e`  |
| **Testklasse** | [`SupplyPledgeReceiveE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/SupplyPledgeReceiveE2eTest.java) · [`SupplyIncomingUnitSwitchE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/SupplyIncomingUnitSwitchE2eTest.java) · [`SupplyStatsLiveE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/SupplyStatsLiveE2eTest.java) · [`SupplyItemPickerE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/SupplyItemPickerE2eTest.java) · [`SupplyNeedsTableLayoutE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/SupplyNeedsTableLayoutE2eTest.java) |
| **Spec**       | [`supply.md`](../specs/supply.md) (REQ-SUP-003/-007/-009/-013/-021) · [`frontend-ajax-mutations.md`](../specs/frontend-ajax-mutations.md) (REQ-FE-001/-010/-016) |

## Akteur

`test-admin` (ADMIN, IRIDIUM-Mitglied) — als spendendes Mitglied und als empfangende Einheit. Der Statistik-Test öffnet zwei Sitzungen.

## Vorbedingungen

- Alle Klassen brauchen den ephemeren Stack.
- Die Bedarf-Funktion ist eingeschaltet (`PUT /api/v1/supply/admin/settings`) und für die beteiligten Einheiten freigegeben (`PUT /api/v1/supply/admin/units/{id}`).
- **Einheitenwechsel:** eine zweite, frische Staffel als empfangende Einheit.
- **Item-Picker:** ein im Lager buchbares Item (`seedStockableItem`).
- **Tabellen-Layout:** so viele offene Bedarfe, dass die Seite scrollt, darunter einer mit langem Materialnamen.

## Auslöser

Ein Mitglied sagt eine Spende zu, die Einheit bestätigt den Empfang, wechselt die empfangende Einheit, legt einen Bedarf an oder sieht die Statistik an.

## Hauptablauf

### Zusage und Empfang (`SupplyPledgeReceiveE2eTest`, REQ-SUP-007, REQ-SUP-009)

1. Auf „Meine Spenden" (`/supply/donations/mine`) eine freie Spende zusagen.
2. Auf „Eingehende Spenden" (`/supply/donations/incoming`) die Zusage als empfangen bestätigen.

### Empfangende Einheit wechseln (`SupplyIncomingUnitSwitchE2eTest`, REQ-FE-001, REQ-FE-010)

3. Auf „Eingehende Spenden" eine andere empfangende Einheit wählen.

### Statistik live (`SupplyStatsLiveE2eTest`, REQ-SUP-013, REQ-FE-010)

4. Sitzung B öffnet `/supply/stats`; Sitzung A erfasst auf „Eingehende Spenden" eine Spende.

### Item-Picker des Bedarfsformulars (`SupplyItemPickerE2eTest`, REQ-SUP-003, REQ-FE-016)

5. Auf `/supply/needs` das Bedarfsformular auf ein Item stellen, ohne Auswahl absenden, im Picker suchen, dann das Formular schließen und wieder öffnen.

### Tabellen-Layout (`SupplyNeedsTableLayoutE2eTest`, REQ-SUP-021)

6. `/supply/needs?status=OPEN&size=50` öffnen, scrollen und das Zeilenmenü öffnen; dasselbe in der Tablet-Referenzbreite.

## Erwartetes Ergebnis

- **Zusage und Empfang:** Beide Seiten aktualisieren sich in place, ohne Reload.
- **Einheitenwechsel:** Die Liste tauscht in place, die Seite wechselt in den Live-Raum der neuen Einheit, und die Adresse zieht mit — ohne Reload.
- **Statistik:** Die Statistikseite der zweiten Sitzung aktualisiert sich in place.
- **Item-Picker:** Der Picker sucht die vom Lager buchbaren Items auf dem Server; ein auf Item gestelltes Formular verlangt eine Auswahl, bevor es etwas sendet; ein wieder geöffnetes Formular startet mit leerem Picker.
- **Tabellen-Layout:** Zeilenaktionen, angeheftete Spaltenköpfe und das nach oben öffnende Menü bleiben im Blick; auf dem Tablet bleiben langer Materialname und Zeilenaktionen im Inhaltsbereich.

## Sonderfälle & Lehren

- **Die Funktion ist schaltbar.** Jede Klasse schaltet Bedarf & Spenden und die beteiligten Einheiten im Setup selbst frei und hängt so von keiner Schwesterklasse ab.
