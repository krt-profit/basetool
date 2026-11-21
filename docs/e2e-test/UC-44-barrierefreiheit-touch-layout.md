# UC-44 — Barrierefreiheit & Layout je Geräteklasse

|                |        |
|----------------|--------|
| **ID**         | UC-44  |
| **Tag**        | `e2e` · `smoke` (`AccessibilitySmokeE2eTest`) · `e2e` (alle übrigen) |
| **Testklasse** | [`AccessibilitySmokeE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/AccessibilitySmokeE2eTest.java) · [`TouchClassLayoutE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/TouchClassLayoutE2eTest.java) · [`FilterPopoverBottomSheetE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/FilterPopoverBottomSheetE2eTest.java) · [`OverflowMenuViewportE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/OverflowMenuViewportE2eTest.java) · [`DialogA11yE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/DialogA11yE2eTest.java) · [`RowLinkHitAreaE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/RowLinkHitAreaE2eTest.java) · [`FilterResetTouchTargetE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/FilterResetTouchTargetE2eTest.java) |
| **Spec**       | [`ui-design-system.md`](../specs/ui-design-system.md) (REQ-UI-009, REQ-UI-013, REQ-UI-020, REQ-UI-027) · [ADR-0172](../adr/0172-the-phone-class-gets-its-own-layout-contract.md) · [ADR-0177](../adr/0177-the-app-has-exactly-one-dialog-shape.md) · [ADR-0239](../adr/0239-the-browser-baseline-is-baseline-2025-and-trusted-types-follow.md) |

## Akteur

Ein angemeldeter Nutzer — ephemer `test-admin`, gegen Staging (nur Barrierefreiheit) der dort konfigurierte Nutzer.

## Vorbedingungen

- Eine einmal angemeldete Session.
- **Layout-Sweep:** Die Klasse läuft als **letzte** der Suite (`@Order(Integer.MAX_VALUE)`, `ClassOrderer.OrderAnnotation` in `junit-platform.properties`), damit die Tabellen die Zeilen der zerstörenden CRUD-Flows enthalten — ein frischer Stack verbirgt, was sie finden soll.

## Auslöser

Jede Seite der Pfadkataloge wird geladen und vermessen bzw. gescannt.

## Hauptablauf

### Barrierefreiheit (`AccessibilitySmokeE2eTest`)

1. Parametrisiert über `FrontendPageRoutes#A11Y_SMOKE` — `/`, `/missions`, `/orders`, `/refinery-orders`, `/hangar`, `/inventory/my` — jede Seite laden.
2. axe-core über Playwrights `evaluate` in die Seite spritzen und die Regeln `wcag2a`, `wcag2aa`, `wcag21a`, `wcag21aa` ausführen.

### Layout je Geräteklasse (`TouchClassLayoutE2eTest`)

3. Für jede Geräteklasse einen eigenen Browser-Kontext öffnen: 375×812 (Smartphone), 810×1080 und 1024×768 (Tablet hoch und quer), 1280×800 (Desktop), 1600×900 (Ultra-wide). In CI misst jeder Runner genau eine Klasse (`-Pe2e.device`, Matrix in `e2e.yml`).
4. Jede Seite aus `FrontendPageRoutes#PAGES` laden und vermessen, dazu jedes Modal der Seite öffnen; alle Befunde sammeln und erst am Ende scheitern.

### Filter als Bottom-Sheet (`FilterPopoverBottomSheetE2eTest`)

5. `/orders` in 375×812 mit Touch öffnen und „Filter" antippen.

### Überlaufmenü im Viewport (`OverflowMenuViewportE2eTest`, REQ-UI-027)

6. Auf dem Smartphone das „⋯"-Menü der Auftrags-Detailseite öffnen, dessen Knopf mitten in der Zeile sitzt.

### Dialog-Vertrag (`DialogA11yE2eTest`, REQ-UI-013, ADR-0177)

7. Den Dialog „Schiff hinzufügen" im Hangar über seinen echten Knopf öffnen, mit Escape schließen und erneut öffnen.
8. Jede Seitenroute und jede geseedete Detailseite besuchen und jeden gerenderten Dialog öffnen und schließen; dabei jede Trusted-Types-Verletzung der Report-only-Policy mitschneiden (ADR-0239).

### Trefferfläche der Zeilenlinks (`RowLinkHitAreaE2eTest`, REQ-UI-027)

9. Ohne Stack eine Fixture-Listentabelle mit den echten Stylesheets laden, je Geräteklasse Punkte jeder Zeile außerhalb ihrer Bedienelemente anklicken, das Überlaufmenü über einer Nachbarzeile öffnen und die Zeile per Maus und Tastatur fokussieren.

### Zurücksetzen der Filter-Chips (`FilterResetTouchTargetE2eTest`, REQ-UI-009)

10. Ohne Stack die echte Chip-Leiste in einer Fixture-Seite je Geräteklasse vermessen und ihren Zurücksetzen-Knopf (`data-filter-chips-reset`) antippen.

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
- **Filter-Bottom-Sheet:** Das Panel reicht über die volle Viewport-Breite, endet genau an der Oberkante der Tab-Leiste, und das Typ-Feld liegt vollständig in seinem sichtbaren Bereich (REQ-UI-027).
- **Überlaufmenü:** Das Panel öffnet unter seinem Knopf und überragt keinen der beiden Bildschirmränder.
- **Dialoge:** Jeder Dialog öffnet modal, hält den Fokus in sich, macht den Hintergrund `inert` und schließt mit Escape und ✕; der Fokus kehrt zum Auslöser zurück. Jede deklarierte `modalId` ist geprüft oder in `UNREACHED` gelistet, und keine Trusted-Types-Verletzung ist aufgetreten — eine absichtlich ausgelöste auf der ersten Seite beweist, dass der Mitschnitt sie hört. Alle Befunde scheitern gesammelt am Ende.
- **Zeilenlinks:** Jeder Punkt außerhalb eines Bedienelements öffnet den Link der eigenen Zeile; Knopf, Menü-Schalter und jeder Eintrag des offenen Menüs fangen ihre eigenen Klicks. Chevron und Titel leuchten bei Zeilen-Hover und Tastaturfokus orange, in Ruhe und beim Hover über ein Bedienelement nicht.
- **Filter-Chips:** Zurücksetzen- und Hinzufügen-Knopf (`.filter-add`) sind auf den Touch-Klassen 44 px und auf dem Desktop 32 px hoch, ein Eintrag des Überlaufmenüs 44 bzw. 40 px; der Klick leert den aktiven Filter und blendet die Chip-Leiste aus.

## Sonderfälle & Lehren

- **Die Dialog-Tour ist die längste Methode der Suite** (bis etwa 4,5 Minuten auf WebKit) und trägt ein eigenes `@Timeout` von acht Minuten (siehe *Zeitlimit je Test* in der [Übersicht](README.md)).
- **Warum beide Tags nur bei der Barrierefreiheit:** Der axe-Scan liest nur und läuft deshalb auch im nächtlichen Staging-Smoke. Der Layout-Sweep trägt **bewusst nicht** `smoke` (Entscheidung des Eigentümers, 2026-09-13): der Smoke-Workflow hat 15 Minuten und keinen Geräte-Filter, und der Sweep öffnet jedes Modal auf jeder Route — mehr, als der nicht-destruktive Smoke-Vertrag gegen eine echte Umgebung erlaubt.
- **axe läuft trotz strikter CSP**, weil Playwrights `evaluate` in einer isolierten Welt läuft. Die Schwelle begann bei `critical`; nachdem #441 den `serious`-Rückstand abgebaut hatte, wurde sie verschärft. Die geloggten `moderate`/`minor`-Befunde sind der Weg zur nächsten Stufe.
- **810×1080 statt 768×1024.** Die Breakpoints (`width <= 768px`) sind inklusiv; ein 768 px breiter Eintrag wäre als Smartphone gerendert und gemessen worden, und das Band 769–1023 px hätte niemand geprüft.
- **Rechtecke, nicht `scrollWidth`.** Überlauf im Padding eines Containers zeigt `scrollWidth - clientWidth` nicht; dieselbe Lehre hält `MissionDatetimeSplitLayoutE2eTest` fest ([UC-45](UC-45-ui-regressionen-formular-navigation.md)).
- **Flex-`fieldset` in WebKit.** WebKit 2388 bemisst die Kinder eines `fieldset` mit `display: flex` mitunter nach ihrem Inhalt statt nach der Breite des `fieldset`, und zwar nur, wenn die Seite mit schon geladenen Schriften gelayoutet wird: Der erste Aufruf ist sauber, die folgenden meist nicht. Auf `/orders/create` stand die Materialzeile so auf 484 px in einem 375 px breiten Viewport; `min-width: 0` auf der Zeile ändert daran nichts, ein einspaltiges Grid statt Flexbox im `fieldset` schon. Prüfen lässt sich so ein Befund nur mit mehreren Aufrufen derselben Seite in einem Kontext, nicht mit einem einzelnen.
- **Ein Kontext pro Klasse** statt `setViewportSize` pro Seite: Einige Skripte werten die Media Queries beim Laden aus, und eine nachträglich verkleinerte Seite ist ein Zustand, den ein Telefon nie erreicht.
- **Artefakte nur aus Chromium.** Jede Engine misst und scheitert an ihren eigenen Befunden, aber nur der Chromium-Shard schreibt die Screenshots nach `build/e2e-artifacts/touch-layout/` — ~430 Aufnahmen dreifach kosteten den Firefox-Shard seinen 45-Minuten-Timeout.
- **Kaskade über Dateigrenzen.** `filters.css` lädt nach `styles.css`, beide im Layer `components`. Eine Telefonregel für `.filter-popover__panel` in `styles.css` verliert deshalb gegen die gleich spezifische Grundregel in `filters.css`: Das Panel blieb `absolute` mit `top` und `bottom` zugleich und schrumpfte auf einen Streifen unter dem Button. Die Telefonregeln einer Komponente stehen darum in ihrer eigenen Datei, nach den Grundregeln.
- **Warum das zählt:** Seit REQ-UI-020 ist die installierbare Web-App der mobile Client für iPhone und iPad.
