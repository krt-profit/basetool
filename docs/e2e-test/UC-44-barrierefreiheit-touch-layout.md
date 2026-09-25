# UC-44 — Barrierefreiheit & Layout je Geräteklasse

|                |        |
|----------------|--------|
| **ID**         | UC-44  |
| **Tag**        | `e2e` · `smoke` (`AccessibilitySmokeE2eTest`) · `e2e` (`TouchClassLayoutE2eTest`) |
| **Testklasse** | [`AccessibilitySmokeE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/AccessibilitySmokeE2eTest.java) · [`TouchClassLayoutE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/TouchClassLayoutE2eTest.java) |
| **Spec**       | [`ui-design-system.md`](../specs/ui-design-system.md) (REQ-UI-009, REQ-UI-020) · [ADR-0172](../adr/0172-the-phone-class-gets-its-own-layout-contract.md) |

## Akteur

Ein angemeldeter Nutzer — ephemer `test-admin`, gegen Staging (nur Barrierefreiheit) der dort konfigurierte Nutzer.

## Vorbedingungen

- Eine einmal angemeldete Session.
- **Layout-Sweep:** Die Klasse läuft als **letzte** der Suite (`@Order(Integer.MAX_VALUE)`, `ClassOrderer.OrderAnnotation` in `junit-platform.properties`), damit die Tabellen die Zeilen der zerstörenden CRUD-Flows enthalten — ein frischer Stack verbirgt, was sie finden soll.

## Auslöser

Jede Seite der Pfadkataloge wird geladen und vermessen bzw. gescannt.

## Hauptablauf

### Barrierefreiheit (`AccessibilitySmokeE2eTest`)

1. Parametrisiert über `FrontendPageRoutes#A11Y_SMOKE` — `/`, `/missions`, `/orders`, `/refinery-orders`, `/hangar` — jede Seite laden.
2. axe-core über Playwrights `evaluate` in die Seite spritzen und die Regeln `wcag2a`, `wcag2aa`, `wcag21a`, `wcag21aa` ausführen.

### Layout je Geräteklasse (`TouchClassLayoutE2eTest`)

3. Für jede Geräteklasse einen eigenen Browser-Kontext öffnen: 375×812 (Smartphone), 810×1080 und 1024×768 (Tablet hoch und quer), 1280×800 (Desktop), 1600×900 (Ultra-wide). In CI misst jeder Runner genau eine Klasse (`-Pe2e.device`, Matrix in `e2e.yml`).
4. Jede Seite aus `FrontendPageRoutes#PAGES` laden und vermessen, dazu jedes Modal der Seite öffnen; alle Befunde sammeln und erst am Ende scheitern.

## Erwartetes Ergebnis

- **Barrierefreiheit:** Keine Verletzung der Stufen `critical` oder `serious`. Jede Verletzung jeder Stufe wird nach `build/e2e/a11y-<seite>.txt` geschrieben und geloggt, mit Selektor, Markup und Fehlerbeschreibung jedes betroffenen Knotens (bei `color-contrast` samt Farben und Kontrastwert; bis 2026-09-25 nur die Anzahl der Knoten).
- **Layout:** Je Seite und Klasse:
  1. Die Seite scrollt nicht seitwärts (breite Tabellen scrollen in ihrem eigenen Container, nicht das Dokument).
  2. Header und — wo angeheftet — Footer belegen zusammen höchstens 33 % der Viewport-Höhe.
  3. Der Footer verhält sich je Klasse: auf dem Smartphone `static` und `--krt-footer-height: 0px`, darüber `fixed`, und das untere Padding von `main` deckt seine gemessene Höhe.
  4. Nichts ist abgeschnitten: kein Element ragt ohne scrollbaren Vorfahren über den rechten Rand.
  5. Eine Tabelle breiter als der Viewport hat einen scrollenden Vorfahren.
  6. Formularfelder passen in den Viewport, und ihre **wirksame** Trefferfläche (inklusive `::after`-Overlay oder aktivierendem `<label>`) erreicht auf den Touch-Klassen die 44-px-Untergrenze aus REQ-UI-009, mit deren Ausnahme für dichte Zeilenaktionen.
- Ein Abdeckungsbericht nennt jede Listenseite, die keine Zeile rendert, und eine Mindestzahl gemessener Routen je Klasse muss erreicht sein.

## Sonderfälle & Lehren

- **Warum beide Tags nur bei der Barrierefreiheit:** Der axe-Scan liest nur und läuft deshalb auch im nächtlichen Staging-Smoke. Der Layout-Sweep trägt **bewusst nicht** `smoke` (Entscheidung des Eigentümers, 2026-09-13): der Smoke-Workflow hat 15 Minuten und keinen Geräte-Filter, und der Sweep öffnet jedes Modal auf jeder Route — mehr, als der nicht-destruktive Smoke-Vertrag gegen eine echte Umgebung erlaubt.
- **axe läuft trotz strikter CSP**, weil Playwrights `evaluate` in einer isolierten Welt läuft. Die Schwelle begann bei `critical`; nachdem #441 den `serious`-Rückstand abgebaut hatte, wurde sie verschärft. Die geloggten `moderate`/`minor`-Befunde sind der Weg zur nächsten Stufe.
- **810×1080 statt 768×1024.** Die Breakpoints (`width <= 768px`) sind inklusiv; ein 768 px breiter Eintrag wäre als Smartphone gerendert und gemessen worden, und das Band 769–1023 px hätte niemand geprüft.
- **Rechtecke, nicht `scrollWidth`.** Überlauf im Padding eines Containers zeigt `scrollWidth - clientWidth` nicht; dieselbe Lehre hält `MissionDatetimeSplitLayoutE2eTest` fest ([UC-45](UC-45-ui-regressionen-formular-navigation.md)).
- **Ein Kontext pro Klasse** statt `setViewportSize` pro Seite: Einige Skripte werten die Media Queries beim Laden aus, und eine nachträglich verkleinerte Seite ist ein Zustand, den ein Telefon nie erreicht.
- **Artefakte nur aus Chromium.** Jede Engine misst und scheitert an ihren eigenen Befunden, aber nur der Chromium-Shard schreibt die Screenshots nach `build/e2e-artifacts/touch-layout/` — ~430 Aufnahmen dreifach kosteten den Firefox-Shard seinen 45-Minuten-Timeout.
- **Warum das zählt:** Seit REQ-UI-020 ist die installierbare Web-App der mobile Client für iPhone und iPad.
