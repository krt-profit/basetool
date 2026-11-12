# UC-27 — Benachrichtigung: erhalten & als gelesen markieren

|                |                                                                                                                                      |
|----------------|--------------------------------------------------------------------------------------------------------------------------------------|
| **ID**         | UC-27                                                                                                                                |
| **Tag**        | `e2e`                                                                                                                                |
| **Testklasse** | [`NotificationCenterE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/NotificationCenterE2eTest.java) |
| **Spec**       | [`notifications.md`](../specs/notifications.md), REQ-FE-001/002                                                                      |

## Akteur

Empfänger ist `test-admin` (globaler Admin). Auslöser der Benachrichtigung ist `test-member` — Erzeuger und Empfänger **müssen** verschieden sein, da die Regeln den Akteur ausschließen.

## Vorbedingungen

- `test-admin` hält vor dem Ereignis die gespeicherte Basetool-Rolle `Admin`. Der `JOB_ORDER_CREATED`-Selektor `ROLE ADMIN` liest seine Empfänger aus `user_roles`.
- `test-member` legt einen Job Order für die IRIDIUM-Staffel an (`createJobOrder`); die geseedete Regel benachrichtigt die Verantwortlichen der Einheit **plus** globale Admins, ohne den Akteur.
- Die `displayId` des Auftrags (aus `GET /api/v1/orders/{id}`) macht den Benachrichtigungstext (`New job order #<displayId> for <Einheit>`) auf dem geteilten Stack eindeutig lokalisierbar.

## Auslöser

Der Admin öffnet `/notifications` und markiert die Benachrichtigung des geseedeten Auftrags als gelesen.

## Hauptablauf

1. Navigiere als Admin zu `/notifications`.
2. Lokalisiere die Zeile über den Text `#<displayId>` (negativer Lookahead `(?!\d)`, damit `#12` nicht `#128` trifft); sie ist ungelesen und trägt den Mark-Read-Button (`data-notif-mark-read`).
3. **Als gelesen markieren:** Klick → `POST /notifications/{id}/read` (`krtFetch.write`, kein Toast). In place: die Zeile erhält `data-notif-read="true"` + `is-read`, der Mark-Read-Button verschwindet.

## Erwartetes Ergebnis

Die geseedete Benachrichtigung erscheint in der Liste (Nachweis, dass die Ereignis-→-Benachrichtigung-Erzeugung greift). Nach dem Markieren ist die Zeile gelesen und ihr Mark-Read-Button weg, ohne Reload (`window.__krtNoReload` überlebt) und ohne Fehler-Toast.

## Sonderfälle & Lehren

- **Akteur-Ausschluss:** Erzeuger (`test-member`) und Empfänger (`test-admin`) sind bewusst verschieden; ein einzelner User würde seine eigene Aktion nie sehen.
- **Empfängerrolle vor dem Ereignis:** Der Teststack hinterlegt die Basetool-Rolle `Admin` beim Start. `getUserId(test-admin)` stellt beim Anmelden den Testnutzer bereit.
- **Geteilter Stack:** Schwester-Suiten legen ebenfalls Aufträge an, daher nie absolute Zähler prüfen, nur die spezifische Zeile über die `displayId` und ihren Zustandswechsel.
- **Live-SSE bewusst nicht geprüft:** Best-effort-Push mit gejittertem Reconnect und ≤60-s-Poll-Fallback ist deutlich flakiger — die persistierte Liste nach Navigation ist der robuste Vertrag.
