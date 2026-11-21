# UC-54 — Spieldaten-Katalog: P4K-Importbericht & Quellanreicherung

|                |        |
|----------------|--------|
| **ID**         | UC-54  |
| **Tag**        | `e2e`  |
| **Testklasse** | [`P4kImportReportE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/P4kImportReportE2eTest.java) · [`CatalogueEnrichmentE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/CatalogueEnrichmentE2eTest.java) |
| **Spec**       | [`p4k-catalogue.md`](../specs/p4k-catalogue.md) (REQ-P4K-015, REQ-P4K-016) · [`data-persistence.md`](../specs/data-persistence.md) (REQ-DATA-037) |

## Akteur

- `test-admin` — die Admin-Seite des P4K-Imports.
- `test-member` — ein einfaches Mitglied, das die angereicherten Katalogseiten liest.

## Vorbedingungen

- Beide Klassen brauchen den ephemeren Stack und seeden per JDBC (`seedSql`).
- **Importbericht:** ein geseedeter Importlauf mit Änderungen und Review-Kandidaten (`p4k-report-e2e-seed.sql`).
- **Anreicherung:** Quelldaten zu einem Material, einem Spiel-Item, einem Schiffstyp und einer Blaupause (`catalogue-enrichment-e2e-seed.sql`), ein Schiff im Hangar von `test-member` und dessen IRIDIUM-Mitgliedschaft; eine direkte Verbindung zum Redis des Stacks, um das Quellsignal zu senden.

## Auslöser

Ein Admin arbeitet den Bericht eines P4K-Imports durch; eine neue Quellversion wird festgeschrieben, während ein Mitglied Katalogseiten offen hat.

## Hauptablauf

### Importbericht und Review (`P4kImportReportE2eTest`, REQ-P4K-015, REQ-P4K-016)

1. `/admin/p4k-import` öffnen, den geseedeten Lauf wählen, den Bericht filtern und auf Seite 2 blättern.
2. Einzelne Änderungen behalten (`KEEP`) und ausblenden (`HIDE`).
3. Eine Review-Gruppe öffnen und über die Sammelaktion mit Vorschau bestätigen; einen Kandidaten im Detail mit Vergleichsvorschau verknüpfen.

### Quellanreicherung live (`CatalogueEnrichmentE2eTest`, REQ-DATA-037)

4. In vier Tabs die Material-Detailseite, die Item-Detailseite (`/inventory/game-item/{id}`), den Hangar und die Blaupausen-Seite öffnen; warten, bis jede das Thema `catalogue` abonniert hat.
5. Eine geänderte Quellversion seeden (`catalogue-enrichment-e2e-update.sql`) und das Signal `basetool:livesync:changed` mit dem Thema `catalogue` über Redis senden.

## Erwartetes Ergebnis

- **Importbericht:** Listen und Vorschauen aktualisieren sich in place; Gruppen- und Verknüpfungsaktionen laufen über eine Vorschau, die erst ihre Bestätigung ausführt.
- **Anreicherung vor der Änderung:** Die Seiten zeigen die Quellangaben samt Build, unbekannte und noch nicht geladene Beziehungen als solche, Quell-Markup als Text, aus dem SC Wiki abgeleitete UEX-Angaben als keine unabhängige Preisbestätigung und ein leeres Rezept mit seinem Leerzustand.
- **Nach dem Signal:** Alle vier Seiten zeigen den neuen Build und die neuen Werte in place — ohne Reload und ohne Fehler-Toast.

## Sonderfälle & Lehren

- **Das Quellsignal kommt nicht aus dem Frontend.** Der Test schreibt die Änderung per JDBC und veröffentlicht das Signal selbst auf dem Redis-Kanal, den sonst das Backend bedient; das Frontend muss es konsumieren (mindestens ein Empfänger), sonst scheitert der Test.
