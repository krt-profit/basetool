# UC-39 — Lager & Hangar: Ansichten, Filter, Paginierung & Live-Sync

|                |        |
|----------------|--------|
| **ID**         | UC-39  |
| **Tag**        | `e2e`  |
| **Testklasse** | [`InventoryStackViewE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/InventoryStackViewE2eTest.java) · [`InventoryFilterPanelCollapseE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/InventoryFilterPanelCollapseE2eTest.java) · [`LagerLocationFilterE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/LagerLocationFilterE2eTest.java) · [`InventorySharedLagerLiveSyncE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/InventorySharedLagerLiveSyncE2eTest.java) · [`FilterPersistenceE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/FilterPersistenceE2eTest.java) · [`HangarPaginationE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/HangarPaginationE2eTest.java) |
| **Spec**       | [`inventory-lager.md`](../specs/inventory-lager.md) (REQ-INV-002/-037/-040) · [`ui-design-system.md`](../specs/ui-design-system.md) (REQ-UI-017) · [`personal-hangar-overview.md`](../specs/personal-hangar-overview.md) (REQ-HANGAR-002) · [ADR-0003](../adr/0003-inventory-append-only-group-on-read.md) · [ADR-0120](../adr/0120-per-browser-filter-selection-persistence.md) |

## Akteur

`test-admin` (ADMIN, IRIDIUM-Mitglied). Der Live-Sync-Test nutzt zwei Browser-Kontexte desselben Nutzers.

## Vorbedingungen

- **Gruppenansicht:** ein nicht-persönlicher Lagereintrag, der weder an einen Auftrag noch an einen Einsatz geknüpft ist.
- **Ortsfilter:** Bestand an zwei geseedeten Orten.
- **Live-Sync:** eine geteilte, auftragsfähige Zeile über 100 SCU und ein Auftrag.
- **Hangar:** so viele Schiffe, dass mehr als eine Seite entsteht.
- Filterpanel- und Filter-Persistenz-Tests brauchen keinen Seed: die Widgets rendern auch auf leerem Stand.

## Auslöser

Der Nutzer öffnet die Lager-Ansichten oder den Hangar und filtert, klappt, blättert — oder ein anderer Logistiker ändert eine Zuordnung, während er zusieht.

## Hauptablauf

1. **Gruppenansicht** (ADR-0003, REQ-INV-002): `/inventory/all` laden.
2. **Filterpanel „Mein Lager"** (REQ-INV-037): erster Besuch ohne Filter, dann aufklappen und neu laden, zuklappen und neu laden; danach die Checkbox „persönliche Einträge" setzen.
3. **Filterpanel „Globales Lager":** derselbe Ablauf mit eigenem Speicherschlüssel; der Zähler wird am Ende über das Mindestqualitäts-Select getrieben.
4. **Ortsfilter** (REQ-INV-040): auf `/inventory/my` den Reload-Marker setzen und einen der zwei Orte anhaken.
5. **Live-Sync des geteilten Lagers** (#1307): beide Kontexte klappen denselben Stack auf `/inventory/all` auf; B wartet, bis `window.krtLiveSync.subscribedTopics()` `inventory` enthält. A fügt über „+ Zuordnen" einen Auftrags-Chip hinzu (`POST /inventory/{id}/allocation`); B lädt nie neu.
6. **Filter-Persistenz** (REQ-UI-017, ADR-0120): auf `/refinery-orders` den Status `COMPLETED` zur Vorauswahl hinzufügen und „nur meine" setzen; auf `/missions` `showPast` einschalten; auf `/materialboerse` die Sortierung ändern — jeweils neu laden.
7. **Hangar** (REQ-HANGAR-002): `/hangar?size=10` laden, eine Seite weiter blättern, dann eine Suche ohne Treffer eingeben.

## Erwartetes Ergebnis

- Der ungeknüpfte Bestand erscheint als Materialgruppe (`div.tree-row--group` mit seiner `data-material-id`).
- Das Filterpanel startet beim ungefilterten Erstbesuch zugeklappt, und jede ausdrückliche Wahl überlebt den Reload; ein gesetzter Filter lässt den Zähler-Chip am Umschalter aufleuchten — auf beiden Lager-Seiten.
- Der Ortsfilter tauscht die Gruppentabelle **in place**: der Stack am gewählten Ort bleibt, der andere verschwindet, dessen Option bleibt wählbar, der Filter-Chip zählt die neue Dimension, und der Marker überlebt.
- B zeigt den neuen Chip **in place**, allein durch das Signal `inventory/[stock]`.
- Alle drei Filterwahlen sind nach dem Reload wiederhergestellt und wirken (der `showPast`-Zustand treibt den Neuabruf der Ergebnisse).
- Der Hangar zeigt höchstens 10 Zeilen, Paginierung und Seitengrößen-Auswahl; Blättern und Suchen tauschen die Tabelle ohne Reload, die Seitenanzeige rückt vor, die Suche ohne Treffer leert die Tabelle.

## Sonderfälle & Lehren

- **Implizite INNER JOINs verschluckten Bestand** (v0.4.0): Die Group-on-read-Abfragen projizierten die nullbaren Assoziationen `jobOrder`/`mission`/`owningOrgUnit` als ganze Entitäten, was jeden Stack ohne diese Verknüpfung still fallen ließ — `/inventory/all` zeigte „Keine Einträge gefunden", während `/inventory` den Bestand listete. Das Daten-Gegenstück ist `InventoryItemStackQueryDataTest`. Der Klassen-Qualifier `tree-row--group` ist tragend: dieselbe `data-material-id` steht auch auf den versteckten Stack-Zeilen.
- **Das Einklappen existiert nur im Browser.** Der Server rendert das Panel immer offen, damit ein Client ohne JavaScript seine Filter behält; `inventory-my.js` und `inventory-admin.js` wenden die gespeicherte Wahl an. Ein toter `data-trigger` oder ein falscher Speicherschlüssel ließe das von `InventoryPageControllerMvcTest` geprüfte Markup unverändert. Ein eingeklappter Filter ohne Zähler wäre der verbotene Zustand: eine eingeengte Tabelle, deren Grund unsichtbar ist.
- **Über den Socket fließen keine Bestandsdaten.** B holt nach dem Signal seine eigene, berechtigte Sicht neu; der gefilterte Fragment-Abruf erhält den aufgeklappten Baum.
- **Die Filter-Persistenz schloss eine Audit-Lücke:** Die Refinery-Warteschlange speicherte nicht, obwohl die Auftrags-Warteschlange es tat; die Börsen-Sortierung ging selbst bei F5 verloren, weil ihre Fragment-Tausche `history:false` laufen. Suche und Datumsbereich der Einsatzliste bleiben bewusst ungespeichert.
- **Der Hangar-Test prüft Struktur, nicht Inhalt,** damit andere Schiffe im geteilten Stack ihn nicht stören.
