# UC-40 — Organigramm, Bereichs-Hierarchie & Ernennungen

|                |        |
|----------------|--------|
| **ID**         | UC-40  |
| **Tag**        | `e2e`  |
| **Testklasse** | [`OrgChartPositionCrudE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/OrgChartPositionCrudE2eTest.java) · [`OrgChartKeyboardA11yE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/OrgChartKeyboardA11yE2eTest.java) · [`OrgHierarchyVisibilityMatrixE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/OrgHierarchyVisibilityMatrixE2eTest.java) · [`RoleAppointmentMatrixE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/RoleAppointmentMatrixE2eTest.java) |
| **Spec**       | [`org-chart.md`](../specs/org-chart.md) (REQ-ORG-010, -012, -013, -025) · [`org-unit-tenancy.md`](../specs/org-unit-tenancy.md) (REQ-ORG-015/-016/-017) · [`role-model.md`](../specs/role-model.md) (REQ-ROLE-006) |

## Akteure

- `test-admin` — bearbeitet das Organigramm und seedet Bereiche und Ernennungen.
- `test-bereich` — Realm-Nutzer nur mit `KRT Member` und ohne Staffel; wird Bereichsleiter von Bereich A.
- `test-appoint-bl` (Bereichsleiter dieser Suite) und `test-appoint-tgt` (Ziel ohne Staffel) — eigene Realm-Nutzer der Ernennungsmatrix.
- `test-member` — einfaches Staffelmitglied.

## Vorbedingungen

- **Organigramm:** Das Bootstrap-Opt-in der IRIDIUM-Staffel reicht für ein nicht leeres, editierbares Organigramm; das Organigramm ist beschreibend und nicht OrgUnit-gescopt. Jeder CRUD-Test löscht zuerst vorhandene Kommandos der IRIDIUM-Staffel, damit das Limit von vier Kommandos je Staffel nicht greift.
- **Hierarchie:** Der Admin legt zwei Bereiche an und je einen Lagereintrag im Besitz jedes Bereichs (Anlegen im Auftrag, REQ-ORG-016, je eigenes Material) und gibt `test-bereich` die Rolle `LEITER` in Bereich A.
- **Ernennungen:** `test-appoint-bl` wird Bereichsleiter von Bereich A dieser Suite; `test-member` bekommt nur sicher eine IRIDIUM-Mitgliedschaft.

## Auslöser

Ein Admin pflegt das Organigramm per Maus und Tastatur; ein Bereichsleiter liest Lager-Daten oder ernennt Rollen.

## Hauptablauf

### Positionen im Organigramm (`OrgChartPositionCrudE2eTest`)

1. Auf `/org-chart` ein Kommando ohne Leiter anlegen, umbenennen und entfernen.
2. Ein Kommando anlegen, einen Freitext-Kommandoleiter setzen und neu besetzen, den Sitz räumen, dann die ganze Gruppe entfernen.

### Tastatur und Barrierefreiheit (`OrgChartKeyboardA11yE2eTest`, REQ-ORG-013)

3. Im Baum mit End, Home, Pfeil rechts und Pfeil links navigieren.
4. Den Editor-Dialog öffnen, Tab und Shift+Tab drücken, mit Esc schließen.
5. Auf einem schmalen Viewport einige Kommandospalten anlegen, bis das Organigramm horizontal scrollt, ganz nach rechts scrollen und das rechteste Kommando umbenennen.

### Bereichs-Sichtbarkeit (`OrgHierarchyVisibilityMatrixE2eTest`, #700)

6. Per API: `test-bereich` und `test-member` lesen die Lager-Daten beider Bereiche; `test-bereich` ruft einen ADMIN-Endpunkt (die Admin-Liste der Org-Hierarchie) auf.
7. In der UI: `test-bereich` lädt `/inventory/all`.

### Ernennungsmatrix (`RoleAppointmentMatrixE2eTest`, epic #800)

8. Per API als ernennender Nutzer: der Bereichsleiter ernennt einen Koordinator im eigenen Bereich, jemanden im fremden Bereich und einen weiteren Bereichsleiter; ein einfaches Mitglied ernennt eine Bereichsrolle und vergibt einen Staffelrang; der Admin gibt einem Staffelmitglied einen Silo-Leitungsrang; das Organigramm wird nach der Ernennung gelesen.

## Erwartetes Ergebnis

- **CRUD:** Der Gruppenkopf spiegelt jeden Schritt; nach dem Räumen des Sitzes **überlebt** die Kommandogruppe (REQ-ORG-025), das Entfernen nimmt sie samt Sitzen weg (REQ-ORG-012). Die Besetzung wird über `data-display-name` am Neu-besetzen-Element und über das Vorhandensein des Räumen-Elements geprüft.
- **Tastatur:** Genau ein `treeitem` hat `tabindex="0"` — nach jeder Bewegung; End/Home springen zum letzten/ersten Knoten, Pfeil rechts/links steigen eine Ebene ab und wieder auf. Solange der Dialog offen ist, ist die Seite dahinter `inert` und `aria-hidden`, Tab bleibt im `.krt-modal`, Esc schließt, und der Fokus kehrt zum auslösenden Element zurück. Nach dem Umbenennen stimmt `scrollLeft` exakt mit dem Wert vor dem Speichern überein.
- **Sichtbarkeit:** Der Bereichsleiter sieht den Bestand seines Bereichs, nie den des fremden; ein einfaches Staffelmitglied sieht keinen Bereichsbestand; der ADMIN-Endpunkt bleibt **403** (REQ-ORG-015). In der UI erscheint nur die Gruppenzeile des eigenen Bereichs.
- **Ernennungen:** Koordinator im eigenen Bereich — erlaubt; fremder Bereich und weiterer Bereichsleiter — verweigert; einfaches Mitglied — **403** auf beiden Endpunkten; Silo-Rang für ein Staffelmitglied — **400** vom Service-Wächter, bevor der DB-Trigger (V165/V187) feuert (REQ-ORG-017). Die Ernennung projiziert den kontogebundenen Sitz ins Organigramm (REQ-ROLE-006).

## Sonderfälle & Lehren

- **Das Organigramm setzt nur Freitext-Namen.** Kontogebundene Sitze werden seit REQ-ROLE-006 unter Organisation → Leitung verwaltet; der Ernennungstest prüft deren Spiegelung.
- **Laufzeit-ARIA gibt es nur im Browser.** Die statischen Rollen und `aria-level`s prüft `OrgChartPageRenderTest`; Roving Tabindex, Fokusfalle und Scroll-Erhalt entstehen im Inline-JavaScript der Seite.
- **Warum rechts umbenennen:** Der Editor fokussiert beim Schließen das auslösende Element, und `focus()` scrollt es in den Blick. Liegt das Element am rechten Rand, während ganz rechts gescrollt ist, bewegt der Fokus nichts. Ein Umbenennen ändert zudem die Breite nicht, sodass der Wert exakt passen muss.
- **Eigene Profile statt geteilter Nutzer.** Eine Leitungsrolle gibt einem Nutzer für den Rest des sequentiell laufenden Stacks eine Bereichsmitgliedschaft. `test-none` darf nie Mitglied werden — `RefineryOrderTenancyE2eTest` und `InventoryTenancyE2eTest` verlassen sich darauf. Deshalb haben Hierarchie- und Ernennungstest eigene Nutzer; `test-member` ist nur ein abgewiesenes Ziel und wird nie verändert.
- **Kaskade und OL-Reichweite** (Bereichsleiter → untergeordnete Staffel/SK) prüft die Unit-Ebene (`OwnerScopeServiceTest.CascadingScopeTests`); die Ernennungslogik zusätzlich `OrgRoleManagementSecurityServiceTest` und `OrgHierarchyMigrationTest`.
- **Mutierende Tests**, deshalb `@Tag("e2e")` und nie gegen ein geteiltes Staging.
