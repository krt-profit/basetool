# UC-38 — Materialien: Preisübersicht & Preiskalkulation

|                |        |
|----------------|--------|
| **ID**         | UC-38  |
| **Tag**        | `e2e`  |
| **Testklasse** | [`MaterialsOverviewMatrixRendersE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/MaterialsOverviewMatrixRendersE2eTest.java) · [`MaterialsOverviewFilterPersistenceE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/MaterialsOverviewFilterPersistenceE2eTest.java) · [`MaterialsProfitCalculationRendersE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/MaterialsProfitCalculationRendersE2eTest.java) |
| **Spec**       | [`materials-pages-completeness.md`](../specs/materials-pages-completeness.md) (REQ-UI-016) · [ADR-0093](../adr/0093-eliminate-inline-style-attributes-csp-style-src-attr-none.md) |

## Akteur

`test-admin` — darf die Handelsseiten öffnen.

## Vorbedingungen

Keine geseedeten UEX-Daten nötig. Die drei Lesetests sind ziel-agnostisch und verändern nichts.

## Auslöser

Der Nutzer öffnet `/materials/overview` oder `/materials/profit-calculation`.

## Hauptablauf

1. **Preisübersicht rendert:** `/materials/overview` laden und die Konsole mitschneiden.
2. **Filter überleben den Reload** (REQ-UI-016): die beiden Boolean-Filter „hat Ladedock" und „Auto-Load" setzen, wo der Katalog mehrere Materialien bietet auch den Material-Multiselect einengen, dann neu laden.
3. **Preiskalkulation rendert:** `/materials/profit-calculation` laden, ein Schiff wählen (entfällt, wenn der Stack keine Schiffe kennt), die Konsole mitschneiden.

## Erwartetes Ergebnis

- Der Matrix-Container `#tableContainer` wird sichtbar, Lade- und Fehlerbox sind versteckt, und keine CSP-Verletzung `style-src-attr` steht in der Konsole.
- Nach dem Reload sind die Filter wiederhergestellt, **und** die erste Anfrage an `/materials/overview/data` trägt sie schon — die Wiederherstellung passiert vor dem ersten Abruf, nicht als kosmetisches Nachsetzen nach einem ungefilterten Laden.
- Der Ergebnisbereich der Preiskalkulation kommt in einem Endzustand an — `#profitResults` trägt `data-state="ready"`, `"empty"` oder `"error"` (Ergebniszeilen oder die Zustandszeile darunter) —, ohne CSP-Verletzung.

## Sonderfälle & Lehren

- **Die CSP-Härtung ließ die Preisübersicht leer** (ADR-0093, ausgeliefert in v1.3.2): Die Vorlage ersetzte `style="display:none"` durch eine Klasse, `materials-matrix.js` setzte aber weiter `style.display = ''` — das Leeren eines leeren Inline-Stils hebt die Klasse nicht auf, die Seite zeigte nur die Filterleiste. Die Abstandszeilen des virtuellen Scrollens trugen zudem ein Inline-`style="height:…"`. Der Fix schaltet die Klasse und setzt die Höhe über das CSSOM (`data-krtm-height` → `style.height`), das `style-src-attr` nicht regelt. Der Daten-Endpunkt antwortet immer mit `200` (bei einem Backend-Fehler ein leeres Raster), deshalb läuft der Erfolgspfad auch ohne UEX-Daten.
- **Dasselbe in der Preiskalkulation:** `materials-profit-calculation.js` baute Status-Zeilen per `innerHTML` mit Inline-Stilen; sie wanderten in die Klassen `profit-msg*`. Seit der Website-Überarbeitung (Phase 3, 2026-10-03) steht der Zustand nicht mehr als Tabellenzeile, sondern als Leerzustand `#profitState` unter der Tabelle, gesteuert über `data-state` am Ergebnisbereich; die Klassen `profit-msg*` gibt es nicht mehr.
- **Filter-Persistenz** anderer Seiten prüft [UC-39](UC-39-lager-hangar-ansichten-filter.md).
