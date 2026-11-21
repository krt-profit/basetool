# UC-39 — Lager & Hangar: Ansichten, Filter, Paginierung & Live-Sync

|                |        |
|----------------|--------|
| **ID**         | UC-39  |
| **Tag**        | `e2e`  |
| **Testklasse** | [`InventoryStackViewE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/InventoryStackViewE2eTest.java) · [`InventoryFilterPanelCollapseE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/InventoryFilterPanelCollapseE2eTest.java) · [`LagerLocationFilterE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/LagerLocationFilterE2eTest.java) · [`InventorySharedLagerLiveSyncE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/InventorySharedLagerLiveSyncE2eTest.java) · [`FilterPersistenceE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/FilterPersistenceE2eTest.java) · [`HangarPaginationE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/HangarPaginationE2eTest.java) · [`LagerTreeDisclosureE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/LagerTreeDisclosureE2eTest.java) · [`InventoryMultiSelectLabelE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/InventoryMultiSelectLabelE2eTest.java) · [`LagerItemAttributeFilterE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/LagerItemAttributeFilterE2eTest.java) · [`LagerTreeLastRowE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/LagerTreeLastRowE2eTest.java) · [`LagerViewSwitchFixtureE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/LagerViewSwitchFixtureE2eTest.java) · [`HangarBulkUnitE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/HangarBulkUnitE2eTest.java) |
| **Spec**       | [`inventory-lager.md`](../specs/inventory-lager.md) (REQ-INV-002/-037/-040) · [`inventory-items.md`](../specs/inventory-items.md) (REQ-INV-063) · [`ui-design-system.md`](../specs/ui-design-system.md) (REQ-UI-007/-009/-017) · [`personal-hangar-overview.md`](../specs/personal-hangar-overview.md) (REQ-HANGAR-002/-010) · [ADR-0003](../adr/0003-inventory-append-only-group-on-read.md) · [ADR-0120](../adr/0120-per-browser-filter-selection-persistence.md) |

## Akteur

`test-admin` (ADMIN, IRIDIUM-Mitglied). Der Live-Sync-Test nutzt zwei Browser-Kontexte desselben Nutzers.

## Vorbedingungen

- **Gruppenansicht:** ein nicht-persönlicher Lagereintrag, der weder an einen Auftrag noch an einen Einsatz geknüpft ist.
- **Ortsfilter:** Bestand an zwei geseedeten Orten.
- **Live-Sync:** eine geteilte, auftragsfähige Zeile über 100 SCU und ein Auftrag.
- **Hangar:** so viele Schiffe, dass mehr als eine Seite entsteht.
- **Aufklappen per Tastatur:** je ein geteilter Lagereintrag zweier eigener Materialien an einem eigenen Ort.
- Filterpanel- und Filter-Persistenz-Tests brauchen keinen Seed: die Widgets rendern auch auf leerem Stand.
- **Item-Attributfilter:** bestellbare Items (Quantenantriebe S2 und S1, ein Schild S2) mit Bestand auf beiden Lager-Seiten.
- **Hangar-Auswahlleiste:** die IRIDIUM-Mitgliedschaft; die beiden Schiffe legt der Test selbst über die Seite an.
- **Letzte Baumzeile, Ansichtswechsel:** kein Stack — die Tests laden die echten Stylesheets und Skripte in eine statische Fixture-Seite (`StaticFixtureServer`).

## Auslöser

Der Nutzer öffnet die Lager-Ansichten oder den Hangar und filtert, klappt, blättert — oder ein anderer Logistiker ändert eine Zuordnung, während er zusieht.

## Hauptablauf

1. **Gruppenansicht** (ADR-0003, REQ-INV-002): `/inventory/all` laden.
2. **Filter-Popover „Mein Lager"** (REQ-INV-037, REQ-UI-027): Seite laden, „Filter" öffnen, mit Escape schließen, neu laden; dann im Popover „Gestohlen:" setzen und in der Werkzeugleiste „Persönlich" wählen.
3. **Filter-Popover „Einheitslager":** derselbe Ablauf; der Zähler wird über das Mindestqualitäts-Select getrieben.
4. **Ortsfilter** (REQ-INV-040): auf `/inventory/my` den Reload-Marker setzen und einen der zwei Orte anhaken.
5. **Live-Sync des geteilten Lagers** (#1307): beide Kontexte klappen denselben Stack auf `/inventory/all` auf; B wartet, bis `window.krtLiveSync.subscribedTopics()` `inventory` enthält. A fügt über „+ Zuordnen" einen Auftrags-Chip hinzu (`POST /inventory/{id}/allocation`); B lädt nie neu.
6. **Filter-Persistenz** (REQ-UI-017, ADR-0120): auf `/refinery-orders` das Segment „Abgeschlossen" wählen und den Schalter „Meine Aufträge" setzen; auf `/missions` `showPast` einschalten; auf `/materialboerse` die Sortierung ändern — jeweils neu laden.
7. **Hangar** (REQ-HANGAR-002): `/hangar?size=10` laden, eine Seite weiter blättern, dann eine Suche ohne Treffer eingeben.
8. **Aufklappen per Tastatur** (REQ-INV-002): auf `/inventory?storage=my` vom Gruppen-Haken mit Shift+Tab auf den Pfeil der Materialgruppe gehen, mit Enter die Gruppe und mit der Leertaste ihren Stack öffnen, den offenen Baum samt geladener Einträge mit axe scannen, neu laden, dann beide Ebenen per Tastatur wieder schließen; auf 810×1080 und 375×812 die Pfeile von Gruppe und Stack vermessen. Den Gruppen-Haken der noch nie geöffneten Gruppe per Maus anklicken, die Gruppe und ihren Stack über die Pfeile öffnen, dann den Haken per Leertaste zweimal umschalten; ein zweites Material am selben Ort liegt daneben.
9. **Beschriftung der Mehrfachauswahl** (`InventoryMultiSelectLabelE2eTest`, REQ-INV-037): auf „Mein Lager" und „Einheitslager" im Materialfilter keinen, alle, einen und mehrere Einträge anhaken.
10. **Item-Attributfilter** (`LagerItemAttributeFilterE2eTest`, REQ-INV-063): in der Item-Ansicht beider Lager-Seiten Komponententyp „Quantenantrieb" und Größe S2 anhaken, dann Klasse „Militär" und S2; die Seite erneut besuchen, „Alle markieren" und „Alle zurücksetzen" auslösen.
11. **Letzte sichtbare Baumzeile** (`LagerTreeLastRowE2eTest`, REQ-UI-007, REQ-INV-002): je Auf- und Zuklappzustand des Baums die unteren Trennlinien der sichtbaren Zeilen messen.
12. **Ansichtswechsel** (`LagerViewSwitchFixtureE2eTest`): zwischen den Lager-Ansichten wechseln, während ein Peer-Signal eintrifft, und einen Wechsel scheitern lassen.
13. **Hangar-Auswahlleiste** (`HangarBulkUnitE2eTest`, REQ-HANGAR-010): in „Meine Schiffe" zwei Schiffe anlegen und markieren, über die Auswahlleiste einer eigenen Einheit zuordnen und wieder auf „Keine Einheit" setzen.

## Erwartetes Ergebnis

- Der ungeknüpfte Bestand erscheint als Materialgruppe (`div.tree-row--group` mit seiner `data-material-id`).
- Das Popover startet bei jedem Laden geschlossen, öffnet über „Filter" und schließt mit Escape; ein gesetzter Filter lässt den Zähler am „Filter"-Knopf aufleuchten und erscheint als Chip — auf beiden Lager-Seiten. Das Segment „Alle · Persönlich · Gemeinsam" engt den Baum ein, ohne den Zähler zu erhöhen.
- Der Ortsfilter tauscht die Gruppentabelle **in place**: der Stack am gewählten Ort bleibt, der andere verschwindet, dessen Option bleibt wählbar, der Filter-Chip zählt die neue Dimension, und der Marker überlebt.
- B zeigt den neuen Chip **in place**, allein durch das Signal `inventory/[stock]`.
- Alle drei Filterwahlen sind nach dem Reload wiederhergestellt und wirken (der `showPast`-Zustand treibt den Neuabruf der Ergebnisse).
- Der Hangar zeigt höchstens 10 Zeilen, Paginierung und Seitengrößen-Auswahl; Blättern und Suchen tauschen die Tabelle ohne Reload, die Seitenanzeige rückt vor, die Suche ohne Treffer leert die Tabelle.
- Der Pfeil ist eine fokussierbare Schaltfläche mit dem orangen 2-px-Fokusrahmen, deren `aria-controls` den Container nennt; `aria-expanded` folgt jedem Auf- und Zuklappen, der erste Aufruf des Stacks lädt seine Einträge, nach dem Reload sind beide Ebenen wieder offen, und axe meldet im Baum samt Einträgen nichts der Stufen `critical` oder `serious` — auch jeder Auswahlhaken eines Eintrags trägt einen Namen. Auf beiden Touch-Klassen trägt jeder Pfeil eine 44 px große Trefferfläche in einer mindestens 44 px hohen Zeile, und der Name bleibt neben ihm. Der Gruppen-Haken klappt nichts und markiert die ganze Gruppe: auf der zugeklappten Gruppe bleibt er gesetzt, die Auswahlleiste zählt genau ihren einen Eintrag (nicht den des zweiten Materials), die Gruppe bleibt zu und kein Stack wird geladen; nach dem Öffnen ist der Eintrag schon markiert, ein entfernter Haken hebt die Markierung auf und blendet die Leiste aus, ein erneut gesetzter markiert wieder.
- Beide Lager-Seiten beschriften die Auswahl gleich: keiner oder alle angehakt heißt „alle", einer zeigt seinen Namen, sonst „N ausgewählt" (gelesen aus `data-all` / `data-selected`, also sprachunabhängig).
- Die Attributfilter engen die Item-Ansicht in place auf den S2-Quantenantrieb ein, zeigen zwei Chips und zählen sie am Filter-Knopf, überleben den erneuten Besuch über die gespeicherte Auswahl und speisen „Alle markieren"; „Alle zurücksetzen" bringt die übrigen Items zurück, ohne Reload.
- Die letzte sichtbare Zeile des Baums zieht keine untere Linie auf den Rahmen des Listenpanels, jede sichtbare Zeile darüber behält ihre.
- Eine Peer-Aktualisierung wartet, bis der Ansichtswechsel steht, und ein gescheiterter Wechsel lässt die bisherige Ansicht samt Filtersteuerung stehen.
- Beide Schiffe wechseln in place zur Einheit und zurück zu „Keine Einheit", und die Auswahlleiste verschwindet nach jeder Aktion (REQ-FE-001).

## Sonderfälle & Lehren

- **Implizite INNER JOINs verschluckten Bestand** (v0.4.0): Die Group-on-read-Abfragen projizierten die nullbaren Assoziationen `jobOrder`/`mission`/`owningOrgUnit` als ganze Entitäten, was jeden Stack ohne diese Verknüpfung still fallen ließ — `/inventory/all` zeigte „Keine Einträge gefunden", während `/inventory` den Bestand listete. Das Daten-Gegenstück ist `InventoryItemStackQueryDataTest`. Der Klassen-Qualifier `tree-row--group` ist tragend: dieselbe `data-material-id` steht auch auf den versteckten Stack-Zeilen.
- **Das Schließen existiert nur im Browser.** Der Server rendert die Filter im Formular, damit ein Client ohne JavaScript sie behält; `krt-filter-panel.js` schließt das Popover beim Laden. Seit der Website-Überarbeitung (Phase 3, 2026-10-03) merkt es sich keinen offenen oder geschlossenen Zustand mehr — der frühere Speicherschlüssel `krt.filterPanel.inventory-*` wird nicht mehr geschrieben. Ein geschlossener Filter ohne Zähler und ohne Chip wäre der verbotene Zustand: eine eingeengte Tabelle, deren Grund unsichtbar ist.
- **Über den Socket fließen keine Bestandsdaten.** B holt nach dem Signal seine eigene, berechtigte Sicht neu; der gefilterte Fragment-Abruf erhält den aufgeklappten Baum.
- **Die Filter-Persistenz schloss eine Audit-Lücke:** Die Refinery-Warteschlange speicherte nicht, obwohl die Auftrags-Warteschlange es tat; die Börsen-Sortierung ging selbst bei F5 verloren, weil ihre Fragment-Tausche `history:false` laufen. Suche und Datumsbereich der Einsatzliste bleiben bewusst ungespeichert.
- **Der Hangar-Test prüft Struktur, nicht Inhalt,** damit andere Schiffe im geteilten Stack ihn nicht stören.
