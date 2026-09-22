# UC-21 — Refinery Order: Mandanten-Scope (wer sieht/erstellt/bearbeitet/lagert ein)

|                |                                                                                                                                          |
|----------------|------------------------------------------------------------------------------------------------------------------------------------------|
| **ID**         | UC-21                                                                                                                                    |
| **Tag**        | `e2e`                                                                                                                                    |
| **Testklasse** | [`RefineryOrderTenancyE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/RefineryOrderTenancyE2eTest.java) |

## Akteur

Vier Profile über die relevanten Mitgliedschafts-Konstellationen — Admin (IRIDIUM), Staffel-A-Mitglied, Staffel-B + SK-X-Mitglied, Mitglied **ohne** Zugehörigkeit.

## Vorbedingungen

Refinery Orders sind **strict-staffel** (REQ-ORG-003): ein Auftrag ist auf seinen `owning_org_unit_id`-Pool beschränkt und verlässt ihn nie — auch nicht über einen verknüpften öffentlichen Einsatz (BAC-004). Per REST geseedet (nur ephemerer Modus):

- **Nutzer/Mitgliedschaften:** `test-admin` (ADMIN, Staffel A), `test-member` (nur Staffel A — der verlässlich einzelmitgliedschaftliche Nutzer), `test-both` (Staffel B **+** SK X — der dedizierte Mehrfach-Mitglied), `test-none` (keine Mitgliedschaft). Der geteilte `test-sk` wird **nicht** angefasst (siehe Sonderfälle).
- **Je ein Auftrag pro besitzender OrgUnit** (identifiziert über die Id in den Listen-Payloads): Staffel A, Staffel B, SK X und ein **eigentümerloser** (`owningOrgUnit = null`) Auftrag, angelegt vom mitgliedschaftslosen Nutzer. Der SK-X-Auftrag wird von `test-both` (Mitglied der suite-eigenen SK X) angelegt. Zusätzlich ein dedizierter Auftrag für die Einlager-Stempel-Probe und ein einsatz-verknüpfter Auftrag für die BAC-004-Probe.

## Auslöser

Jeder Viewer ruft die gescopten Refinery-Endpunkte ab bzw. öffnet `/refinery-orders`.

## Hauptablauf & Erwartetes Ergebnis

UI treiben, **per API verifizieren** (wie UC-08/UC-14): die Matrix wird durch Abruf der gescopten Endpunkte (`/api/v1/refinery-orders/all`, `…/my-orders`, `…/mission/{id}`) als jeweiliger Nutzer über den `BackendSeeder` geprüft; die org-gescopte Liste wird zusätzlich über die echte `/refinery-orders`-UI (repräsentatives Mitglied) gefahren. Der Admin-Pin wird über den `X-Active-Org-Unit-Id`-Header gesetzt.

Sichtbarkeitsraster in der org-gescopten Liste (`/api/v1/refinery-orders/all`) für einen Auftrag im Besitz von OrgUnit O:

|             Viewer             |          sieht O           |
|--------------------------------|----------------------------|
| Admin ohne Pin                 | alle (inkl. eigentümerlos) |
| Admin mit Pin = O              | nur O                      |
| Mitglied von O                 | ✓                          |
| Mitglied einer anderen OrgUnit | ✗                          |
| Staffel **+** SK-Mitglied      | Vereinigung beider Pools   |
| mitgliedschaftslos             | ✗                          |

Zusätzlich abgedeckt:

- **Persönliche Liste** (`…/my-orders`): rein nutzer-gescopt — der mitgliedschaftslose Ersteller sieht seinen eigentümerlosen Auftrag hier, im org-gescopten `…/all` aber nur der ungepinnte Admin.
- **Create-Stamping** (REQ-ORG-004): 1 Mitgliedschaft → Auto-Stamp; mehrere ohne Auswahl → 400; gültige Auswahl → übernommen; fremde Auswahl → 400; mitgliedschaftslos ohne Auswahl → eigentümerlos.
- **Fremd-Scope-Gates:** ein Viewer außerhalb des besitzenden Pools kann den Auftrag weder lesen (`GET …/{id}` → 403) noch bearbeiten (`PUT` → 403) noch einlagern (`POST …/{id}/store` → 403).
- **Owner-Gate:** ein Staffel-A-Mitglied, das weder Eigentümer noch Logistician ist, kann einen fremden A-Auftrag nicht bearbeiten (Org-Gate passiert, Service-Owner-Check → 403).
- **Einlager-Stempelung:** das Einlagern eines Staffel-B-Auftrags an einen IRIDIUM-Empfänger erzeugt **IRIDIUM**-Bestand (sichtbar für das IRIDIUM-Mitglied, unsichtbar für das B+SK-Mitglied) — die OrgUnit des Empfängers gewinnt, nicht die des Auftrags. Einlagern an einen Mehrfach-Mitglied-Empfänger → 400 (das Store-Formular hat keinen Pro-Output-Picker).
- **BAC-004:** ein auf Staffel B gescopter Viewer sieht über `…/mission/{id}` **keinen** A-Auftrag des organisationsweiten A-Einsatzes; der ungepinnte Admin (All-Scope) schon.

## Sonderfälle & Lehren

- **SK-Mitgliedschaften kumulieren über die sequenziell laufenden Schwester-Suites** (ein einmal hinzugefügtes SK-Mitglied wird nie entfernt). Die Suite fasst daher `test-sk` **nicht** an — eine Schwester-Suite (`InventoryTenancyE2eTest`) verlässt sich auf dessen Einzel-SK-Mitgliedschaft — und repräsentiert den SK-Pool über `test-both` (Mitglied der suite-eigenen SK X). `test-member` (nur IRIDIUM), `test-both` (Mehrfach) und `test-none` (keine) sind die verlässlich eindeutigen Profile; jeder Create übergibt eine **explizite** OrgUnit-Auswahl, wo die Mitgliedschaftszahl des Aufrufers sonst mehrdeutig wäre.
- **`owningOrgUnit.id` liest die FK-Spalte direkt** (ManyToOne, kein Join) — ein eigentümerloser Auftrag (`NULL`-FK) fällt im All-Scope-Zweig des Admins **nicht** aus dem Ergebnis, wird aber für Mitglieder via `IN (:memberOrgUnitIds)` ausgefiltert. Sichtbarkeit identisch zur Inventar-Mandanten-Sicht (UC-14).
- **Admin-Pin verengt** auf die gepinnte OrgUnit (`adminAllScope = false`); damit lässt sich auch der gescopte Einsatz-Roll-up (BAC-004) ohne Mutation eines Logistician-Flags prüfen.
- **Optimistic-Lock vor Owner-Check:** der Owner-Gate-Fall sendet bewusst die **aktuelle** `version`, damit der Versions-Check passiert und der Eigentümer-Check (403) feuert (vgl. den 409-Fall in UC-20).
- **Verifikation via API.** Aufträge werden über ihre Id in den Listen-Payloads erkannt (volle UUID, kein Substring-Risiko); gegen die gescopten Endpunkte als verschiedene Nutzer zu assertieren ist die etablierte, race-freie Methode für Tenancy-Grenzen (vgl. UC-08/UC-14).
