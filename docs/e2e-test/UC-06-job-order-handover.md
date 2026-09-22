# UC-06 — Job-Order-Handover protokollieren

|                |                                                                                                                                  |
|----------------|----------------------------------------------------------------------------------------------------------------------------------|
| **ID**         | UC-06                                                                                                                            |
| **Tag**        | `e2e`                                                                                                                            |
| **Testklasse** | [`JobOrderHandoverE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/JobOrderHandoverE2eTest.java) |

## Akteur

Authentifizierter User mit der Rolle LOGISTICIAN, OFFICER oder ADMIN (IRIDIUM-Mitgliedschaft).

## Vorbedingungen

Die komplette Vorbedingungskette wird per REST geseedet:

- IRIDIUM-Mitgliedschaft (`ensureIridiumMembership`).
- Ein Job-Order-Material (`ensureJobOrderMaterial`) und eine Location (`createLocation`).
- Ein Job Order, der dieses Material anfragt (`createJobOrder`).
- Ein dem Auftrag **verknüpfter Lagereintrag** (`createInventoryItemForJobOrder`: `jobOrderId` gesetzt, gleiches Material, Qualität ≥ `minQuality`).

## Auslöser

Der User öffnet das Handover-Modal auf der Auftragsdetailseite `/orders/{id}`.

## Hauptablauf

1. Navigiere zu `/orders/{jobOrderId}`.
2. Öffne das Modal über `order-handover-open`; das Modal lädt das verknüpfte Inventar **lazy per `fetch`** (`/orders/{id}/materials/{matId}/inventory`). Warte per `waitForResponse` auf diese Antwort, **bevor** eine Zeile hinzugefügt wird.
3. Füge eine Materialzeile hinzu (`add-handover-item-btn`), wähle den Lagereintrag (`items[0].inventoryItemId`) und die Menge (`items[0].amount`).
4. Fülle Übergabezeit (`.date-part` / `.time-part` im Modal) und Empfänger (`#recipientHandle`).
5. Speichern über `order-handover-submit`.

## Erwartetes Ergebnis

- Die Übergabe erscheint in der Handover-Tabelle des Auftrags als `order-handover-row` mit dem Empfänger.
- **Ausbuchung aus dem Lager:** Die übergebene Menge wird vom verknüpften Lagereintrag abgezogen (bei voller Entnahme wird die Zeile gelöscht) und die offene Menge des `JobOrderMaterial` entsprechend reduziert. Der Test prüft das **pro Eintrag** über die Auftrags-Inventarsicht (`GET /api/v1/orders/{id}/materials/{matId}/inventory`, ungegated `findByJobOrderIdOrdered`):
  - **Ein Lagereintrag:** 100 → bei Übergabe von 40 bleibt 60.
  - **Mehrere Lagereinträge in einer Übergabe:** 100 (−40 → 60) und 60 (−30 → 30) werden **je einzeln** in ihrer jeweiligen Menge ausgebucht, nicht gepoolt.

## Sonderfälle & Lehren

- **Concurrency-/409-sensibel:** Der Handover dekrementiert den verknüpften Lagereintrag **und** die offene Menge des Job-Order-Materials in **einer** Transaktion (`*WithinTransaction`-Pattern). Playwrights Auto-Waiting deckt das AJAX-/`data-version`-Timing ab.
- **Snapshot-Dropdown:** Die „Material auswählen"-Schaltfläche snapshottet das gecachte Inventar **beim Klick** in das Dropdown. Wird vor dem `fetch` geklickt, bleibt das Dropdown leer und füllt sich nicht nach → erst auf die `…/inventory`-Antwort warten.
- **Warten auf Antworten, nicht auf Seitenskripte:** Der Test synchronisiert sich über `waitForResponse` (Inventar-Fetch beim Öffnen, Handover-`POST` beim Absenden) und über web-first-Assertions auf den DOM-Zustand, statt im Seitenkontext zu pollen — die Code-Kommentare der Handover-Tests begründen das mit der strikten CSP. Andere Klassen (etwa `BankBookingE2eTest`, `BfcacheRefreshE2eTest`) nutzen `page.waitForFunction` allerdings erfolgreich; die CSP-Begründung ist also keine allgemeine Regel der Suite.
- **`responsibleOrgUnitId` + Profit-Eligibility:** `POST /api/v1/orders` verlangt ein `responsibleOrgUnitId`, das auf eine **profit-eligible** Einheit auflöst (sonst 400; `creatingSquadronId` ist entfallen). Der Stack-Bootstrap schaltet IRIDIUM einmalig profit-eligible (vor dem ersten Seitenaufruf, der den Squadron-Katalog-Cache füllt); `createJobOrder` setzt IRIDIUM als responsible + requesting.
- Übergabezeit nutzt denselben `datetime-split-group`-Mechanismus wie UC-02, hier aber ohne Race (das Modal öffnet lange nach dem Page-Load) und mit `data-validate-not-past='false'`.
- **Nur MATERIAL-Übergaben buchen Lager aus:** Item-Übergaben ([UC-17](UC-17-item-order-handover.md)) erhöhen nur die `deliveredAmount` der Bestellpositionen und rühren das Lager nicht an (`order.type != 'ITEM'`-Gate auf dem Material-Übergabe-Block). Die Ausbuchungs-Prüfung gehört daher zum Material-Flow.
- **Kein Auto-Complete in den Ausbuchungs-Fällen:** Die Ausbuchungs-Aufträge fragen mehr an, als übergeben wird (200 vs. ≤ 100), damit der Auftrag nicht vollständig erfüllt wird — eine Komplettierung würde die verbleibenden Lagereinträge vom Auftrag trennen (Unlink) und sie aus der Auftrags-Inventarsicht entfernen, was die Pro-Eintrag-Assertion unterlaufen würde.
