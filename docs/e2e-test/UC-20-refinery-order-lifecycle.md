# UC-20 — Refinery Order: Lifecycle & Edge Cases (Bearbeiten, Abbrechen, Filter, Validierung)

|                |                                                                                                                                              |
|----------------|----------------------------------------------------------------------------------------------------------------------------------------------|
| **ID**         | UC-20                                                                                                                                        |
| **Tag**        | `e2e`                                                                                                                                        |
| **Testklasse** | [`RefineryOrderLifecycleE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/RefineryOrderLifecycleE2eTest.java) |

## Akteur

Authentifizierter User als Eigentümer/Admin — hier `test-admin` mit IRIDIUM-Mitgliedschaft.

## Vorbedingungen

Zusammen mit UC-04 (Anlegen) und UC-19 (Einlagern) deckt dieser Use Case die restlichen Refinery-Funktionen ab. Per REST geseedet (nur ephemerer Modus):

- IRIDIUM-Mitgliedschaft, die Katalog-Location `E2E Refinery Hub` (`findLocationIdByName`), ein manuelles RAW-Material und **eine gewöhnliche (nicht-Raffinerie-) Location** für die Negativ-Probe.
- **Pro Test ein eigener Auftrag** (`createRefineryOrder`), damit Mutationen (Edit, Abbruch) isoliert bleiben.

## Auslöser

Der User öffnet die Detailseite `/refinery-orders/{id}` (Bearbeiten/Abbrechen) bzw. die Liste `/refinery-orders` (Filter); die Validierungs- und Sperr-Kanten laufen direkt über die REST-API.

## Hauptablauf

1. **Bearbeiten** — Detailseite, `#oreSales` ändern, über den Speichern-Button (`button[form=refineryOrderMainForm]`) absenden → Redirect auf die Liste.
2. **Abbrechen** — Detailseite, Abbrechen-Submit im `…/delete`-Formular → Redirect auf die Liste.
3. **Status-Filter** — Liste: Default-Filter `OPEN`+`IN_PROGRESS` blendet einen abgebrochenen Auftrag aus; nach Anhaken von `CANCELED` + „Filtern" erscheint er.
4. **API-Edges** — Anlegen an einer Nicht-Raffinerie-Location (400), Anlegen mit leerer Waren-Liste (400), Update mit veralteter `version` (409).

## Erwartetes Ergebnis

- Der geänderte Ore-Sales-Wert ist persistiert (GET `/api/v1/refinery-orders/{id}`).
- Der abgebrochene Auftrag steht auf `CANCELED`.
- Der `CANCELED`-Auftrag fehlt im Default-Filter und erscheint nach Anhaken von `CANCELED`.
- Nicht-Raffinerie-Location → **400**; leere Waren-Liste → **400**; veraltete `version` → **409**.

## Sonderfälle & Lehren

- **Liste zeigt nur die letzten 4 Zeichen der Id**, die volle Id steckt aber im Details-Link (`a[href$='/refinery-orders/{id}']`) — das ist der robuste Zeilen-Selektor für Filter- und Scope-Assertions.
- **Geld-Felder: 0 = nicht gesetzt.** `expenses`/`otherExpenses`/`oreSales` werden vom Backend per `zeroToNull` auf `null` normalisiert; der Test prüft daher einen echten Wert (12345) statt 0.
- **Optimistic Locking vor Owner-Check.** `updateRefineryOrder` prüft erst die `version` (409), dann die Eigentümerschaft — eine veraltete Version schlägt also vor dem 403-Pfad zu (vgl. den Owner-Gate-Fall in UC-21).
- **Abbrechen ist ein Soft-Cancel.** `DELETE /api/v1/refinery-orders/{id}` setzt `status=CANCELED`, löscht nichts.
- **Save-Button steht außerhalb des Formulars** und referenziert es via `form="refineryOrderMainForm"`; Playwright klickt ihn dennoch als Submit des verknüpften Formulars.
