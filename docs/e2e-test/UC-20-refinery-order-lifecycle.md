# UC-20 — Refinery Order: Lifecycle & Edge Cases (Bearbeiten, Abbrechen, Filter, Validierung)

|                |                                                                                                                                              |
|----------------|----------------------------------------------------------------------------------------------------------------------------------------------|
| **ID**         | UC-20                                                                                                                                        |
| **Tag**        | `e2e`                                                                                                                                        |
| **Testklasse** | [`RefineryOrderLifecycleE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/RefineryOrderLifecycleE2eTest.java) · [`RefineryOrderLiveSyncE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/RefineryOrderLiveSyncE2eTest.java) |

## Akteur

Authentifizierter User als Eigentümer/Admin — hier `test-admin` mit IRIDIUM-Mitgliedschaft.

## Vorbedingungen

Zusammen mit UC-04 (Anlegen), UC-19 (Einlagern) und UC-24 (Import) deckt dieser Use Case die restlichen Refinery-Funktionen ab. Per REST geseedet (nur ephemerer Modus):

- IRIDIUM-Mitgliedschaft, die Katalog-Location `E2E Refinery Hub` (`findLocationIdByName`), ein manuelles RAW-Material und **eine gewöhnliche (nicht-Raffinerie-) Location** für die Negativ-Probe.
- **Pro Test ein eigener Auftrag** (`createRefineryOrder`), damit Mutationen (Edit, Abbruch) isoliert bleiben.

## Auslöser

Der User öffnet die Detailseite `/refinery-orders/{id}` (Bearbeiten/Abbrechen) bzw. die Liste `/refinery-orders` (Filter); die Validierungs- und Sperr-Kanten laufen direkt über die REST-API.

## Hauptablauf

1. **Bearbeiten** — Detailseite, in der Eingangsmaterial-Combobox die erste angebotene Option wählen, `#oreSales` = `12345` setzen, Footer ausblenden, über den Speichern-Button (`button[form='refineryOrderMainForm']`) absenden; gewartet wird auf die Antwort des Update-`POST`.
2. **Abbrechen** — Detailseite, Abbrechen-Submit im `…/{id}/delete`-Formular; das KRT-Bestätigungsmodal öffnet (#575) und wird mit `.krt-confirm-overlay .krt-confirm-ok` bestätigt; gewartet wird auf die Antwort des Delete-`POST`.
3. **Status-Filter** — `/refinery-orders` (Default `OPEN`+`IN_PROGRESS`) zeigt den abgebrochenen Auftrag nicht; `/refinery-orders?status=CANCELED` zeigt ihn.
4. **API-Edges** — Anlegen an einer Nicht-Raffinerie-Location (400), Anlegen mit leerer Waren-Liste (400), Update mit veralteter `version` (409).
5. **Live-Sync** (`RefineryOrderLiveSyncE2eTest`, #1238) — zwei Browser-Kontexte desselben Nutzers öffnen denselben Auftrag, setzen je den Reload-Marker und warten, bis `window.krtLiveSync.subscribedTopics()` nicht leer ist. Kontext A macht das Formular absendbar (erste Option der Eingangsmaterial-Combobox), ändert Ore-Sales und speichert; Kontext B schaut nur zu.

## Erwartetes Ergebnis

- Der geänderte Ore-Sales-Wert ist persistiert (GET `/api/v1/refinery-orders/{id}`).
- Der abgebrochene Auftrag steht auf `CANCELED`.
- Der `CANCELED`-Auftrag fehlt im Default-Filter und erscheint nach Anhaken von `CANCELED`.
- Nicht-Raffinerie-Location → **400**; leere Waren-Liste → **400**; veraltete `version` → **409**.
- Live-Sync: A rendert den Abschnitt `order` in place neu, B zeigt den neuen Ore-Sales-Wert ohne Reload; **beide** Marker überleben — ein Reload auf einer Seite fiele durch, statt sich als Live-Update auszugeben.

## Sonderfälle & Lehren

- **Filter über die URL, nicht über den Button.** Das Formular der Status-Checkboxen wird per GET genau auf diesen `?status=…`-Vertrag abgeschickt, und sein „Filtern"-Button ist per JS ausgeblendet (Progressive Enhancement) — im Harness nicht klickbar. Die URL ruft denselben Backend-Filter auf wie die UI.
- **Warum die erste Material-Option:** Die Optionen des Eingangsmaterial-Pickers kommen aus der 10 Minuten gecachten Material-Liste, die das frisch geseedete Material noch nicht enthalten muss; ohne Auswahl blockiert die Pflichtfeld-Validierung das Speichern.
- **Liste zeigt nur die letzten 4 Zeichen der Id**, die volle Id steckt aber im Details-Link (`a[href$='/refinery-orders/{id}']`) — das ist der robuste Zeilen-Selektor für Filter- und Scope-Assertions.
- **Geld-Felder: 0 = nicht gesetzt.** `expenses`/`otherExpenses`/`oreSales` werden vom Backend per `zeroToNull` auf `null` normalisiert; der Test prüft daher einen echten Wert (12345) statt 0.
- **Optimistic Locking vor Owner-Check.** `updateRefineryOrder` prüft erst die `version` (409), dann die Eigentümerschaft — eine veraltete Version schlägt also vor dem 403-Pfad zu (vgl. den Owner-Gate-Fall in UC-21).
- **Live-Sync setzt die In-Place-Umstellung voraus.** Speichern, Einlagern und Abbrechen navigierten früher auf die Liste, und die Vorlage hatte keine Fragment-Naht; deshalb konnte die Sweep #1235 diese Seitenfamilie nicht abdecken. Der Test prüft beide Hälften zugleich: das In-Place-Neurendern beim Handelnden und den Push an den Zuschauer über den Raum `refinery-order:{id}` (REQ-FE-015, ADR-0094). Ein blockierter Submit sähe wie ein Live-Sync-Fehler aus — daher die erste Material-Option.
- **Abbrechen ist ein Soft-Cancel.** `DELETE /api/v1/refinery-orders/{id}` setzt `status=CANCELED`, löscht nichts.
- **Save-Button steht außerhalb des Formulars** und referenziert es via `form="refineryOrderMainForm"`; Playwright klickt ihn dennoch als Submit des verknüpften Formulars.
