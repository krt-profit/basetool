# UC-09 — Handover staffel-übergreifend (Material von B, Empfänger ggf. dritte Staffel)

|                |                                                                                                                                          |
|----------------|------------------------------------------------------------------------------------------------------------------------------------------|
| **ID**         | UC-09                                                                                                                                    |
| **Tag**        | `e2e`                                                                                                                                    |
| **Testklasse** | [`CrossStaffelHandoverE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/CrossStaffelHandoverE2eTest.java) |
| **Basis-Flow** | [UC-06](UC-06-job-order-handover.md) · Vorbedingung: [UC-08](UC-08-job-order-staffel-uebergreifend.md)                                   |

## Akteur

`test-officer` (OFFICER, Heimat-Staffel IRIDIUM = Staffel A). Er gehört **nicht** zur Staffel B, der der übergebene Lagereintrag gehört.

## Vorbedingungen

Wie [UC-08](UC-08-job-order-staffel-uebergreifend.md), nur im ephemeren Modus per REST geseedet: Staffel B „E2E HO Staffel B" mit `test-member`, ein Job Order von IRIDIUM und ein von `test-member` an ihn verknüpfter, damit B-besessener Lagereintrag.

## Auslöser

Das von Staffel B beigesteuerte Material wird übergeben — optional an Staffel B als Empfänger-Staffel.

## Hauptablauf

1. `test-officer` öffnet `/orders/{id}?tab=handovers` und das Handover-Modal (`order-handover-open`); der Test wartet auf den Lazy-Fetch des verknüpften Inventars.
2. Zeile hinzufügen, den **B-besessenen** Lagereintrag wählen, Menge 40.
3. Übergabezeit (`.date-part` / `.time-part`) und Empfänger (`#recipientHandle` = „E2E CrossStaffel Recipient") setzen; bietet `#recipientSquadron` die Staffel B an, wird sie gewählt, sonst bleibt das Feld leer.
4. `window.__krtNoReload` setzen und über `order-handover-submit` absenden.

## Erwartetes Ergebnis

- Die Übergabe erscheint **in place** als `order-handover-row` mit dem Empfänger (das beweist auch die Persistenz), und der Reload-Marker ist noch gesetzt — die Material-Übergabe tauscht die Abschnitte per AJAX (#575), ohne Post/Redirect/Get.
- Die Übergabe eines fremd-besessenen Eintrags wird also von einem Officer der anderen Staffel angenommen. Die Ausbuchungsmengen prüft dieser Test nicht; das tut [UC-06](UC-06-job-order-handover.md) für den Ein-Staffel-Fall.

## Sonderfälle & Lehren

- **Keine OrgUnit-Prüfung im Handover:** `JobOrderHandoverService` verlangt nur, dass der Lagereintrag eine Zuordnung zu *diesem* Auftrag trägt (sonst `400` mit `error.job_order.inventory_item_not_linked`, „Der Lagereintrag ist diesem Auftrag nicht (mehr) zugeordnet"; bis 2026-09-22 ein `IllegalStateException`-Text), nicht, dass er der Staffel des Protokollierenden gehört — das ist die Mechanik hinter „B liefert in A's Auftrag, A protokolliert die Übergabe". Das Rollen-Gate (LOGISTICIAN/OFFICER/ADMIN + `canEditJobOrder`) sitzt am Controller.
- **`recipientSquadron`** erfasst die Heimat-Staffel des Empfängers (Freitext-Handle + optionale Staffel-Auswahl) — so ist der Drei-Staffel-Fall (A bestellt, B liefert, C empfängt) dokumentierbar.
- **Concurrency/409:** Der Handover dekrementiert Inventar **und** offene Auftragsmenge in einer Transaktion (`*WithinTransaction`-Pattern); Bulk-Unlinks laufen einmalig nach der Schleife (sonst detachte Entities → 409).
