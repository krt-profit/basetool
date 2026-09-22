# UC-35 — Job Order: Materialbedarf, Herstellung & Lager-Verknüpfung lösen

|                |        |
|----------------|--------|
| **ID**         | UC-35  |
| **Tag**        | `e2e`  |
| **Testklasse** | [`JobOrderMaterialDemandE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/JobOrderMaterialDemandE2eTest.java) · [`JobOrderProductionE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/JobOrderProductionE2eTest.java) · [`JobOrderInventoryUnlinkInPlaceE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/JobOrderInventoryUnlinkInPlaceE2eTest.java) |
| **Spec**       | [`orders-material-demand.md`](../specs/orders-material-demand.md) (REQ-ORDERS-034) · [`orders-item-production.md`](../specs/orders-item-production.md) (REQ-ORDERS-025/-028/-030/-031) · [`inventory-items.md`](../specs/inventory-items.md) (REQ-INV-032) |

## Akteur

`test-admin` (ADMIN, IRIDIUM-Mitglied) — erfüllt die LOGISTICIAN-Gates für Herstellung und Entknüpfen über die Rollenhierarchie.

## Vorbedingungen

Per REST geseedet:

- **Materialbedarf:** zwei Aufträge derselben profit-eligible Einheit über dasselbe Material in derselben Qualität, dazu ein zweites Material, dessen Bedarf der Bestand schon deckt. Die Materialnamen sind nur in dieser Klasse vergeben, damit fremde Aufträge im geteilten Stack die Prüfungen nicht stören.
- **Herstellung:** ein ITEM-Auftrag über eine Einheit des beim Stack-Bootstrap geseedeten Widgets (sein Bauplan leitet genau ein RESOURCE-Material mit 1,0 SCU pro Einheit ab) und ein per API an den Auftrag geknüpfter Lagereintrag genau dieses Materials.
- **Entknüpfen:** ein MATERIAL-Auftrag mit einem geknüpften Lagereintrag; der Auftrag fordert mehr an, als vorhanden ist, damit er nie automatisch abschließt.

## Auslöser

Der Nutzer öffnet die auftragsübergreifende Bedarfsübersicht, bucht die Herstellung eines bestellten Items oder löst einen Lagereintrag von einem Auftrag.

## Hauptablauf

### Materialbedarf über alle Aufträge (`JobOrderMaterialDemandE2eTest`)

1. `/orders/material-demand` öffnen.
2. Die Aufklapp-Details eines Bedarfs öffnen, dann die Seite neu laden.
3. Die Navigation auf den Eintrag zur Seite prüfen (Vorhandensein und Ziel, ohne Klick).
4. Das Filterpanel einklappen, neu laden, aktive Filter setzen.
5. „Gedeckte ausblenden" an- und wieder abschalten.
6. Im Material-Multiselect ein Material abwählen; im Dropdown suchen.
7. Zweimal auf die Spaltenüberschrift „Offen" klicken.

### Herstellung eines ITEM-Auftrags (`JobOrderProductionE2eTest`, REQ-ORDERS-025)

8. Vor der Herstellung die Detailseite des Auftrags prüfen.
9. Im Tab „Bestellte Items" das Herstellungs-Modal öffnen, eine Menge eingeben, den Bedarf dem geknüpften Lagereintrag zuteilen und — Pflicht seit REQ-INV-032 — einen Einbuchungsort in der Combobox wählen; buchen, sobald der Abgleich-Chip volle Deckung meldet.
10. Danach Detailseite, `/inventory/all?view=items` und die Item-Sammelseite (`/item-collection`) prüfen und dort den Eintrag als geliefert markieren.

### Lagereintrag in place entknüpfen (`JobOrderInventoryUnlinkInPlaceE2eTest`, #571)

11. Auf `/orders/{id}` die Materialzeile anklicken (Lazy-Fetch des geknüpften Inventars), den Reload-Marker setzen und am Eintrag den Entknüpfen-Button klicken (`DELETE /orders/{id}/inventory/{invId}/unlink/ajax` über `krtFetch.write`).

## Erwartetes Ergebnis

- **Materialbedarf:** Die beiden Aufträge fallen im Abschnitt der verantwortlichen Einheit in **eine** Zeile, deren Bedarf die Summe ist; deren Aufklappbereich listet beide Aufträge. Die Aufklappbereiche starten zu und bleiben nach dem Reload offen (`localStorage`). Die Navigation führt einen Eintrag auf die Seite. Das Filterpanel merkt sich den eingeklappten Zustand über einen Reload, und sein Umschalter zeigt die Zahl aktiver Filter (das Lager-Muster aus REQ-INV-037). „Gedeckte ausblenden" entfernt genau die gedeckte Zeile und stellt sie wieder her. Das abgewählte Material verschwindet aus der Tabelle; die Dropdown-Suche verengt nur die Optionen. „Offen" sortiert jede Gruppentabelle nach offener Menge und kehrt beim zweiten Klick um — geprüft an der **relativen** Reihenfolge der eigenen zwei Zeilen.
- **Herstellung:** Vor der Herstellung fehlt der Item-Übergabe-Button (Lieferung ist an die Herstellung gebunden). Danach ist `manufacturedAmount` = 1 (per API), der Button erscheint, der Item-Bestand des Widgets ist um genau eine Einheit gewachsen (gruppierte `catalog=ITEM`-API) und steht mit Namen und ganzzahliger Menge in der Item-Ansicht des Lagers. Weil das Einbuchen die Einheit automatisch dem Auftrag zuordnet, zeigt der Tab „Bestellte Items" den zugeordneten Bestand in der Aufklappzeile (REQ-ORDERS-028). Die Geliefert-Markierung je (Eintrag, Auftrag) bleibt gespeichert (REQ-ORDERS-030/031).
- **Entknüpfen:** Der Eintrag ist nicht mehr am Auftrag geknüpft (per `GET /api/v1/orders/{id}/materials/{matId}/inventory` gelesen), `#order-materials-results` wurde in place getauscht, kein Reload, kein Fehler-Toast.

## Sonderfälle & Lehren

- **Die Bedarfsseite ist nach dem HTML reine Client-Logik.** Den Server-Render deckt ein MockMvc-Test ab; Aufklappen, Filter, Sortierung und die `localStorage`-Wiederherstellung erreicht nur der Browser.
- **Navigationslinks werden nicht geklickt.** Sie liegen in eingeklappten `<details>`-Gruppen; ein Klick prüfte das Aufklappverhalten der Seitenleiste statt dieser Funktion.
- **Kein Auto-Abschluss im Entknüpfen-Fall.** Ein abgeschlossener Auftrag löst seine restlichen Einträge selbst; weil der Auftrag mehr anfordert, als vorhanden ist, ist das manuelle Entknüpfen die einzige Ursache.
