# UC-24 — Refinery Order aus Screenshot-Extract importieren

|                |                                                                                                                              |
|----------------|------------------------------------------------------------------------------------------------------------------------------|
| **ID**         | UC-24                                                                                                                        |
| **Tag**        | `e2e`                                                                                                                        |
| **Testklasse** | [`RefineryImportE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/RefineryImportE2eTest.java) |

## Akteur

Authentifizierter User mit IRIDIUM-Mitgliedschaft.

## Vorbedingungen

- Eingeloggte Session (UC-01).
- IRIDIUM-Mitgliedschaft geseedet (`ensureIridiumMembership`).
- Ein RAW-Eingangsmaterial „E2E Import Material" geseedet (`ensureRefineryMaterial`, get-or-create); der Name ist bewusst klassen-eindeutig, weil `RefineryOrderCreateE2eTest` auf demselben Stack bereits „E2E Refinery Material" anlegt. Zusätzlich seedet die Klasse das Material der Schwesterklasse mit — siehe Lehren.
- UEX-Katalog per JDBC geseedet (`seedCatalog`): Location „E2E Refinery Hub" (refinery-fähig) und Refining Method „E2E Refining Method".
- Fixture [`refinery-extract-e2e.json`](../../frontend/src/e2e/resources/refinery-extract-e2e.json): ein quotierter SETUP-Auftrag mit einer exakt matchbaren Zeile („E2E IMPORT MATERIAL") und einer absichtlich vertippten Zeile („E2E IMPRT MATERAIL"), die unter dem Fuzzy-Accept-Threshold bleibt und nur als Vorschlag erscheint.

## Auslöser

Der User wählt auf `/refinery-orders/create` über den Import-Button die Extract-JSON-Datei.

## Hauptablauf

1. Navigiere zu `/refinery-orders/create`.
2. Setze die Fixture-Datei per `setInputFiles` auf das versteckte File-Input (`refinery-import-file`) — **der erste File-Upload-E2E des Repos**; der Change-Handler submittet das Multipart-Formular nach `/refinery-orders/import`.
3. Nach dem Redirect ist das Formular vorbefüllt: Standort, Methode, Zeile 0 (Material + Mengen + Qualität); der Banner (`refinery-import-banner`) zählt die Zuordnung; Zeile 1 trägt das Inline-Flag (`refinery-import-row-flags-1`).
4. Klicke den Vorschlags-Chip (`refinery-import-suggestion-1`) — das Material-Select der Zeile 1 übernimmt den Kandidaten.
5. Submit über `refinery-submit`.

## Erwartetes Ergebnis

Der Auftrag erscheint in der Liste unter `/refinery-orders` als `refinery-order-row`; es wurde nichts vor dem Submit gespeichert (der Import liefert nur einen Draft).

## Sonderfälle & Lehren

- **`setInputFiles` funktioniert auf versteckten Inputs** (Playwright prüft für File-Inputs keine Sichtbarkeit) — der KRT-Pattern „hidden input + gestylter Trigger-Button" braucht keinen Klick auf den nativen Dialog.
- **Fixture-Namen müssen die Matching-Stufen treffen:** „E2E IMPRT MATERAIL" ist so gewählt, dass weder Canonical- noch Suffix-Matching greift und der Fuzzy-Score (~0,84) unter dem Accept-Threshold 0,9 bleibt, aber über dem Vorschlags-Floor 0,5 liegt.
- Der Import-POST ist ein normaler Form-Submit (Flash-Attribute + Redirect), kein AJAX — `awaitFormPost` wartet wie beim Create-Submit auf das Settling des Redirects.
- **Flash-Maps brauchen String-Keys:** Die Flash-Attribute reisen durch die JSON-serialisierte Redis-Session; eine `Map<Integer, …>` kommt nach dem Redirect mit String-Keys zurück, `containsKey(int)` läuft ins Leere und kein Inline-Flag rendert (so auf CI gefunden — die Render-Unit-Tests umgehen die Session und können das nicht sehen). `importRowIssues` ist deshalb String-keyed; `RedisSessionImportFlashRoundTripTest` pinnt das Verhalten.
- **Der Materials-Lookup-Cache (10 min) friert beim ersten Create-Page-Render ein:** Jede Klasse, die das Create-Formular öffnet, muss vorher die *Vereinigung* aller Dropdown-Materialien idempotent seeden (`ensureRefineryMaterial`), sonst fehlt der später geseedeten Schwesterklasse ihr Material im Dropdown (`selectOption`-Timeout).
