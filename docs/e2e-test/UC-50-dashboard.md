# UC-50 — Dashboard: Startboard, Widgets & Admin-Board

|                |        |
|----------------|--------|
| **ID**         | UC-50  |
| **Tag**        | `e2e`  |
| **Testklasse** | [`DashboardE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/DashboardE2eTest.java) · [`DashboardSupplyNeedsE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/DashboardSupplyNeedsE2eTest.java) · [`AdminDashboardE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/AdminDashboardE2eTest.java) |
| **Spec**       | [`ui-design-system.md`](../specs/ui-design-system.md) (REQ-UI-034) · [`supply.md`](../specs/supply.md) |

## Akteur

- `test-member` — das Mitglieder-Board mit seiner Standardbelegung.
- `test-admin` — der Inline-Editor der Information, das Admin-Board und das Widget „Bedarf & Spenden"; nur ein Konto ohne Heimat-Pin erreicht dessen Pfad ohne Pin.

## Vorbedingungen

- Alle Klassen brauchen den ephemeren Stack.
- **Admin-Board:** die IRIDIUM-Mitgliedschaft.
- **Bedarf & Spenden:** die Bedarf-Funktion eingeschaltet (`/api/v1/supply/admin/settings`, je Einheit `/api/v1/supply/admin/units/{id}`), Bedarfe in IRIDIUM und einer frischen Staffel und ein zweiter Leitungsposten des Admins.

## Auslöser

Der Nutzer öffnet `/` bzw. `/admin`, richtet sein Board ein oder ändert Widget-Optionen; ein zweites Fenster desselben Nutzers ist offen.

## Hauptablauf

### Mitglieder-Board (`DashboardE2eTest`)

1. Die Standardbelegung beider Spalten prüfen.
2. Im Bearbeiten-Modus Widgets hoch und runter schieben, die Spalte wechseln, entfernen, aus dem Katalog hinzufügen, Optionen ändern und den Standard wiederherstellen; den Katalog bis zur Obergrenze von zwölf füllen.
3. Die Übersichts-, Arbeits-, Auftrags- und Handels-Widgets laden und ihre Optionen öffnen; ein Auftrags-Widget scheitern lassen.
4. Eine Option nach einem Verschieben ändern; ein zweites, veraltetes Fenster speichern lassen; im zweiten Fenster gespeicherte Optionen über den Abruf ankommen lassen.
5. Das Board in der Smartphone-Klasse öffnen; die Abfragen der Widgets ohne Live-Kanal bei sichtbarer Seite beobachten; den einmaligen Hinweis prüfen.
6. Als Administrator die Information inline bearbeiten.

### Widget „Bedarf & Spenden" (`DashboardSupplyNeedsE2eTest`)

7. Das Widget ohne Pin, mit gepinntem Kontext, mit einer in den Widget-Optionen gewählten Einheit und mit einem Pin auf einer anderen als der gewählten Einheit laden.

### Admin-Board (`AdminDashboardE2eTest`)

8. `/admin` öffnen, im Bearbeiten-Modus den Hub entfernen und wiederherstellen, den Zeitraum des Sync-Status ändern.
9. Die Ankündigung im Widget „Information" bearbeiten und entfernen und die frühere Seite `/admin/announcement` aufrufen.
10. `/admin` als `test-member` aufrufen.

## Erwartetes Ergebnis

- **Mitglieder-Board:** Jede Änderung im Bearbeiten-Modus läuft in place; der Katalog schließt bei zwölf Widgets. Jedes Widget lädt seinen Inhalt und bietet seine Optionen an, und ein scheiterndes Auftrags-Widget lässt die anderen stehen. Eine Optionsänderung nach einem Verschieben läuft ohne Konflikt; das veraltete Fenster lädt das Board neu und sagt es an. Das Smartphone zeigt eine Spalte, Widgets ohne Live-Kanal fragen alle zehn Minuten nach, solange die Seite sichtbar ist, und der Hinweis erscheint einmal und verschwindet, sobald sich das Board ändert.
- **Bedarf & Spenden:** Ohne Pin vereinigt das Widget die eigenen Einheiten; ein Pin engt es auf die gepinnte Einheit ein; die gewählte Einheit gewinnt gegen den Pin; ein Pin auf einer anderen Einheit macht das Widget nicht verfügbar.
- **Admin-Board:** Standard sind der Hub und vier Admin-Widgets; der Sync-Status behält seinen Zeitraum; die Ankündigung wird auf dem Board bearbeitet und entfernt, die frühere Seite leitet um, und ein Mitglied wird abgewiesen.

## Sonderfälle & Lehren

- **Zwei Fenster, ein Board.** Das Board trägt eine Version; ein Fenster mit veraltetem Stand schreibt nicht still über das andere, sondern lädt neu und sagt es an.
