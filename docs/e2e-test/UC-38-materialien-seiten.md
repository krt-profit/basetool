# UC-38 — Materialien: Preisübersicht, Preiskalkulation & Kategorien

|                |        |
|----------------|--------|
| **ID**         | UC-38  |
| **Tag**        | `e2e`  |
| **Testklasse** | [`MaterialsOverviewMatrixRendersE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/MaterialsOverviewMatrixRendersE2eTest.java) · [`MaterialsOverviewFilterPersistenceE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/MaterialsOverviewFilterPersistenceE2eTest.java) · [`MaterialsProfitCalculationRendersE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/MaterialsProfitCalculationRendersE2eTest.java) · [`MaterialsCategoryEmptyStateInPlaceE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/MaterialsCategoryEmptyStateInPlaceE2eTest.java) |
| **Spec**       | [`materials-pages-completeness.md`](../specs/materials-pages-completeness.md) (REQ-UI-016) · [ADR-0093](../adr/0093-eliminate-inline-style-attributes-csp-style-src-attr-none.md) |

## Akteur

`test-admin` — darf die Handelsseiten öffnen und Materialkategorien verwalten.

## Vorbedingungen

Keine geseedeten UEX-Daten nötig. Die drei Lesetests sind ziel-agnostisch und verändern nichts; der Kategorie-Test verlässt sich darauf, dass auf dem ephemeren Stack keine Materialkategorie existiert (weder `DataInitializer` noch SQL-Seed legen eine an).

## Auslöser

Der Nutzer öffnet `/materials/overview` oder `/materials/profit-calculation`; ein Admin pflegt Kategorien auf `/admin/materials`.

## Hauptablauf

1. **Preisübersicht rendert:** `/materials/overview` laden und die Konsole mitschneiden.
2. **Filter überleben den Reload** (REQ-UI-016): die beiden Boolean-Filter „hat Ladedock" und „Auto-Load" setzen, wo der Katalog mehrere Materialien bietet auch den Material-Multiselect einengen, dann neu laden.
3. **Preiskalkulation rendert:** `/materials/profit-calculation` laden, ein Schiff wählen (entfällt, wenn der Stack keine Schiffe kennt), die Konsole mitschneiden.
4. **Leerzustand nach letztem Löschen:** auf `/admin/materials` den Reload-Marker setzen, genau eine Kategorie in place anlegen und sie über das KRT-Bestätigungsmodal in place wieder löschen.

## Erwartetes Ergebnis

- Der Matrix-Container `#tableContainer` wird sichtbar, Lade- und Fehlerbox sind versteckt, und keine CSP-Verletzung `style-src-attr` steht in der Konsole.
- Nach dem Reload sind die Filter wiederhergestellt, **und** die erste Anfrage an `/materials/overview/data` trägt sie schon — die Wiederherstellung passiert vor dem ersten Abruf, nicht als kosmetisches Nachsetzen nach einem ungefilterten Laden.
- Der Ergebnisbereich der Preiskalkulation bekommt eine Zeile, ohne CSP-Verletzung.
- Nach dem Löschen der letzten Kategorie steht der Platzhalter `[data-category-empty]` wieder da, ohne Reload.

## Sonderfälle & Lehren

- **Die CSP-Härtung ließ die Preisübersicht leer** (ADR-0093, ausgeliefert in v1.3.2): Die Vorlage ersetzte `style="display:none"` durch eine Klasse, `materials-matrix.js` setzte aber weiter `style.display = ''` — das Leeren eines leeren Inline-Stils hebt die Klasse nicht auf, die Seite zeigte nur die Filterleiste. Die Abstandszeilen des virtuellen Scrollens trugen zudem ein Inline-`style="height:…"`. Der Fix schaltet die Klasse und setzt die Höhe über das CSSOM (`data-krtm-height` → `style.height`), das `style-src-attr` nicht regelt. Der Daten-Endpunkt antwortet immer mit `200` (bei einem Backend-Fehler ein leeres Raster), deshalb läuft der Erfolgspfad auch ohne UEX-Daten.
- **Dasselbe in der Preiskalkulation:** `materials-profit-calculation.js` baute Status-Zeilen per `innerHTML` mit Inline-Stilen; sie wanderten in die Klassen `profit-msg*`.
- **Leerzustand nach dem No-Reload-Umbau** (epic #571): Das frühere volle Neuladen renderte den Platzhalter neu; das ungeschützte In-Place-Löschen machte nur `row.remove()` und ließ einen Tabellenkopf über einem leeren Körper stehen.
- **Filter-Persistenz** anderer Seiten prüft [UC-39](UC-39-lager-hangar-ansichten-filter.md).
