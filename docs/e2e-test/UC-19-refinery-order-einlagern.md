# UC-19 — Refinery Order einlagern (in das Lager überführen)

|                |                                                                                                                                      |
|----------------|--------------------------------------------------------------------------------------------------------------------------------------|
| **ID**         | UC-19                                                                                                                                |
| **Tag**        | `e2e`                                                                                                                                |
| **Testklasse** | [`RefineryOrderStoreE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/RefineryOrderStoreE2eTest.java) |

## Akteur

Authentifizierter User, der den Auftrag bearbeiten darf (Eigentümer, Logistician oder Admin) — hier `test-admin` mit IRIDIUM-Mitgliedschaft als Eigentümer.

## Vorbedingungen

Der Store-Schritt schließt einen Raffinerieauftrag ab: jede Ausgangs-Ware wird zu einer **append-only** Lagerzeile (REQ-INV-001), der Auftrag wechselt auf `COMPLETED`. Per REST geseedet (nur ephemerer Modus):

- IRIDIUM-Mitgliedschaft (`ensureIridiumMembership`) und die Bootstrap-Katalog-Location `E2E Refinery Hub` (`findLocationIdByName`) als Raffinerie.
- **Pro Test ein eigenes manuelles RAW-Material** (`createRefineryMaterial`, `isManualRawMaterial=true`) plus ein per API angelegter `OPEN`-Auftrag (`createRefineryOrder`). Das Material hat kein veredeltes Pendant, daher setzt das Backend das Ausgangsmaterial gleich dem Eingangsmaterial — genau dieses Material legt der Store-Schritt ins Lager. Eigenes Material je Test → Output ↔ Lagerzeile 1:1, der gruppierte Inventar-Endpunkt liest exakt diesen Beitrag zurück.

## Auslöser

Der User öffnet die Auftrags-Detailseite `/refinery-orders/{id}` und klickt **Einlagern** (`data-trigger="rod-open-store"`).

## Hauptablauf

1. Detailseite öffnen; der **Einlagern**-Button erscheint nur für einen editierbaren `OPEN`/`IN_PROGRESS`-Auftrag.
2. Klick öffnet das Store-Modal (`#storeModal`); der Detail-Controller füllt es aus den Waren des Auftrags vor (Menge, Qualität, Auftrags-Location, aktueller Nutzer) — ein blanker Submit ist also gültig.
3. Den fixierten Footer ausblenden und über `#storeForm button[type=submit]` absenden; der Test wartet auf die Antwort des `POST /refinery-orders/{id}/store`.
4. Ein erfolgreiches Einlagern **bleibt auf der Detailseite** (REQ-FE-001): das Modal schließt, der Auftragsabschnitt wird aus dem nun `COMPLETED`-Auftrag neu gerendert, und der Einlagern-Button verschwindet, weil sein Status-Gate nicht mehr greift.

API-Edges (rein über die REST-API): erneutes Einlagern eines bereits `COMPLETED`-Auftrags, Notiz-Propagation.

## Erwartetes Ergebnis

- Der Auftrag steht nach dem Einlagern auf `COMPLETED` (GET `/api/v1/refinery-orders/{id}`).
- Der raffinierte Output liegt im Lager (`/api/v1/inventory/all/grouped?materialIds=…` ist nun nicht-leer; vorher leer).
- **Erneutes Einlagern** eines `COMPLETED`-Auftrags wird mit **400** abgelehnt (Status-Guard).
- Die im Store-Dialog erfasste **Notiz** steht an der erzeugten Lagerzeile (`/api/v1/inventory/material/{materialId}`).

## Sonderfälle & Lehren

- **Modal ist vorbefüllt.** Menge (Output-Units ÷ 100 → SCU), Qualität, Ziel-Location (= Auftrags-Location) und Mitglied (= aktueller Nutzer) sind alle vorbelegt; der Happy Path ist „Modal öffnen → absenden", ohne ein Pflichtfeld nachzutragen.
- **Status-Guard ist asymmetrisch.** `storeRefineryOrder` lehnt nur einen bereits `COMPLETED`-Auftrag ab (400) — ein `OPEN`/`IN_PROGRESS`/`CANCELED`-Auftrag ist einlagerbar. Genau `COMPLETED` ist die Doppel-Einlagerungs-Sperre.
- **Output-Material = Eingangsmaterial** für ein manuelles RAW ohne `refinedMaterial`; deshalb verifiziert der Test das Lager über die Material-Id des Eingangsmaterials.
- **Stempelung auf die OrgUnit des Empfängers** (nicht des Auftrags) — siehe UC-21; dieser Flow nutzt durchgehend `test-admin`/IRIDIUM, sodass Auftrag und Empfänger denselben Pool teilen.
- **Kein Navigations-Warten.** `E2eSupport#clickSubmitClearingFooter` wird hier bewusst nicht benutzt: es wartet auf ein Dokument nach einer Navigation, und seit #1238 navigiert ein erfolgreiches Einlagern nicht mehr — der Helfer liefe in seinen Timeout. Das Verschwinden des Einlagern-Buttons ist das Erfolgssignal; bei einem Fehler bleibt das Modal mit Button offen.
- **UI treiben, API verifizieren.** Die gruppierte Lager-Ansicht lädt lazy; gegen die gescopten Endpunkte zu assertieren ist die etablierte, race-freie Methode (vgl. UC-13/UC-14).
