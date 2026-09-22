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
3. Lies den Status über `GET /api/v1/orders/{id}` zurück; navigiere dann neu.
4. Wähle `COMPLETED` → der **terminale** Wechsel öffnet erst das Warn-Modal (`#status-warning-modal`); erst das Bestätigen (`[data-trigger='od-confirm-status']`) postet.
5. Lies den Status erneut über die API zurück.

## Erwartetes Ergebnis

Nach jedem Wechsel liefert der Backend-Read-Back den neuen Status (`IN_PROGRESS`, dann `COMPLETED`). Das Warn-Modal ist vor dem Bestätigen sichtbar. (Dass der terminale Wechsel verknüpfte Lagereinträge trennt, ist Backend-Verhalten; dieser Test prüft es nicht.)

## Sonderfälle & Lehren

- **AJAX + Reload:** Bei Erfolg lädt der Client nach ~1 s selbst neu (frischer `@Version`). Eine Prüfung am neu geladenen `#status-select` würde unter CI-Last mit diesem Reload wettlaufen; der Test liest den Status deshalb über die API und navigiert vor dem nächsten Wechsel selbst neu, damit dieser einen aktuellen Stand und keine veraltete Version liest. Auf den Status-POST wird per `waitForResponse` gewartet.
- **Terminal vs. nicht-terminal:** Nur `COMPLETED`/`REJECTED` öffnen das Warn-Modal (sie trennen Inventar — irreversibel); `IN_PROGRESS` postet direkt.
- **Gating:** `#status-select` ist `hasRole('LOGISTICIAN')`-gegatet; ein einfaches Mitglied sieht nur den statischen Status-Badge (kein Dropdown). Das Frontend-Gate ist `isAuthenticated()`, das Backend-Gate `hasRole('LOGISTICIAN')`.
