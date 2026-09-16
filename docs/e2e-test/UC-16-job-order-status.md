# UC-16 — Job-Order-Status ändern

|                |                                                                                                                              |
|----------------|------------------------------------------------------------------------------------------------------------------------------|
| **ID**         | UC-16                                                                                                                        |
| **Tag**        | `e2e`                                                                                                                        |
| **Testklasse** | [`JobOrderStatusE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/JobOrderStatusE2eTest.java) |
| **Basis-Flow** | [UC-03](UC-03-job-order-anlegen.md)                                                                                          |

## Akteur

Authentifizierter User mit der Rolle LOGISTICIAN (oder OFFICER/ADMIN über die Rollenhierarchie) — hier `test-admin`.

## Vorbedingungen

- IRIDIUM-Mitgliedschaft, ein Job-Order-Material, ein frischer `OPEN`-MATERIAL-Auftrag (`createJobOrder`).

## Auslöser

Der User ändert den Status über das Dropdown `#status-select` auf der Auftragsdetailseite.

## Hauptablauf

1. Navigiere zu `/orders/{id}`.
2. Wähle `IN_PROGRESS` → das `change`-Event postet **sofort** an `POST /orders/{id}/status` (kein Modal bei nicht-terminalem Ziel).
3. Navigiere neu und prüfe, dass `#status-select` den Wert `IN_PROGRESS` zeigt.
4. Wähle `COMPLETED` → der **terminale** Wechsel öffnet erst das Warn-Modal (`#status-warning-modal`); erst das Bestätigen (`od-confirm-status`) postet.
5. Navigiere neu und prüfe `COMPLETED`.

## Erwartetes Ergebnis

Nach jedem Wechsel zeigt `#status-select` den persistierten Status. Der terminale Wechsel trennt zusätzlich alle verknüpften Lagereinträge vom Auftrag (Backend) und nullt die Priorität.

## Sonderfälle & Lehren

- **AJAX + Reload:** Bei Erfolg lädt der Client nach ~1 s selbst neu (frischer `@Version`). Der Test navigiert stattdessen explizit nach jedem POST-Settle neu — so liest der nächste Wechsel einen aktuellen Stand und es gibt keinen Stale-Version-409. Auf den `…/status`-POST wird per `waitForResponse` gewartet.
- **Terminal vs. nicht-terminal:** Nur `COMPLETED`/`REJECTED` öffnen das Warn-Modal (sie trennen Inventar — irreversibel); `IN_PROGRESS` postet direkt.
- **Gating:** `#status-select` ist `hasRole('LOGISTICIAN')`-gegatet; ein einfaches Mitglied sieht nur den statischen Status-Badge (kein Dropdown). Das Frontend-Gate ist `isAuthenticated()`, das Backend-Gate `hasRole('LOGISTICIAN')`.
