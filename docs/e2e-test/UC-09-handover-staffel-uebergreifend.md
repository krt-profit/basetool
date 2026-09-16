# UC-09 — Handover staffel-übergreifend (Material von B, Empfänger ggf. dritte Staffel)

|                |                                                                                                                                          |
|----------------|------------------------------------------------------------------------------------------------------------------------------------------|
| **ID**         | UC-09                                                                                                                                    |
| **Tag**        | `e2e`                                                                                                                                    |
| **Testklasse** | [`CrossStaffelHandoverE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/CrossStaffelHandoverE2eTest.java) |
| **Basis-Flow** | [UC-06](UC-06-job-order-handover.md) · Vorbedingung: [UC-08](UC-08-job-order-staffel-uebergreifend.md)                                   |

## Akteur

Ein **Logistician, Officer oder Admin** protokolliert die Übergabe. Er muss **nicht** Mitglied der besitzenden Staffel des Inventars sein — der Handover ist Teil des Cross-Staffel-Workspaces.

## Vorbedingungen

- Ein Job Order von Staffel A (UC-08) mit einem **verknüpften, B-besessenen** Lagereintrag.
- Der protokollierende User hat die Rolle LOGISTICIAN/OFFICER/ADMIN.

## Auslöser

Das von Staffel B beigesteuerte Material wird an einen Empfänger übergeben — der Empfänger kann einer **dritten** Staffel angehören.

## Hauptablauf

1. Öffne `/orders/{id}` und das Handover-Modal (`order-handover-open`); das verknüpfte Inventar wird lazy nachgeladen.
2. Wähle den **B-besessenen** Lagereintrag und die Menge.
3. Fülle Empfänger (`#recipientHandle`) und — für den staffel-übergreifenden Fall — die Empfänger-Staffel (`#recipientSquadron`, z. B. Staffel C).
4. Übergabezeit setzen, `order-handover-submit`.

## Erwartetes Ergebnis

- Die Übergabe wird protokolliert und erscheint als `order-handover-row` (mit Empfänger und ggf. Empfänger-Staffel).
- **B's Lagereintrag wird dekrementiert** (bzw. gelöscht), obwohl der protokollierende User nicht zu B gehört — der Handover-Service validiert nur `item.jobOrder.id == orderId`, **nicht** die OrgUnit des Items.
- Die offene Menge des `JobOrderMaterial` sinkt; ist der Auftrag vollständig erfüllt, wird er abgeschlossen.

## Sonderfälle & Lehren

- **Keine OrgUnit-Prüfung im Handover:** Der Service operiert auf dem an den Auftrag verknüpften Inventar unabhängig vom Besitzer — das ist die Mechanik hinter „B liefert in A's Auftrag, A protokolliert die Übergabe".
- **`recipientSquadron`** erfasst die Heimat-Staffel des Empfängers (Freitext-Handle + optionale Staffel-Auswahl) — so ist der Drei-Staffel-Fall (A bestellt, B liefert, C empfängt) dokumentierbar.
- **Concurrency/409:** Der Handover dekrementiert Inventar **und** offene Auftragsmenge in einer Transaktion (`*WithinTransaction`-Pattern); Bulk-Unlinks laufen einmalig nach der Schleife (sonst detachte Entities → 409).
