# UC-41 — Profil & Standard-Blaupausen

|                |        |
|----------------|--------|
| **ID**         | UC-41  |
| **Tag**        | `e2e`  |
| **Testklasse** | [`ProfileDescriptionInPlaceE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/ProfileDescriptionInPlaceE2eTest.java) · [`ProfilePayoutPreferenceInPlaceE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/ProfilePayoutPreferenceInPlaceE2eTest.java) · [`ProfileBlueprintSharingInPlaceE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/ProfileBlueprintSharingInPlaceE2eTest.java) · [`DefaultBlueprintsE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/DefaultBlueprintsE2eTest.java) |
| **Spec**       | [`frontend-ajax-mutations.md`](../specs/frontend-ajax-mutations.md) (REQ-FE-001/-004) · [`personal-inventory-blueprints.md`](../specs/personal-inventory-blueprints.md) (REQ-INV-016/-017/-018) |

## Akteur

`test-admin` — auf seinem eigenen Profil und, für die Kuratierung, auf der Admin-Seite der Standard-Blaupausen.

## Vorbedingungen

- Eingeloggte Session; für die Profil-Tests kein weiterer Seed.
- **Standard-Blaupausen:** Das Backend legt beim Start die Standard-Blaupausen in `default_blueprint` an (Provisioning im `dev`-Profil, mit dem der E2E-Stack läuft) und vergibt sie einem Nutzer, wenn seine `app_user`-Zeile entsteht — `BackendSeeder#getUserId` erzwingt das im Setup.

## Auslöser

Der Nutzer speichert Angaben auf `/profile` oder öffnet seine Blaupausen; ein Admin kuratiert die Standard-Blaupausen.

## Hauptablauf

### Profil in place speichern

1. **Beschreibung** (REQ-FE-001/-004): Reload-Marker setzen, die Beschreibung speichern; dann das `_csrf`-Meta-Tag der Seite verfälschen und erneut speichern.
2. **Auszahlungs-Präferenz:** auf den Wert wechseln, auf dem das Formular gerade **nicht** steht, speichern (`POST /profile/payout-preference` → `PUT /api/v1/users/me/payout-preference`), dann ohne Reload zurückwechseln und erneut speichern.
3. **Blaupausen-Freigabe** (REQ-INV-018): den Schalter umlegen und speichern (`POST /profile/blueprint-sharing` → `PUT /api/v1/users/me/blueprint-sharing`), dann ohne Reload zurücklegen und erneut speichern.

### Standard-Blaupausen (`DefaultBlueprintsE2eTest`, REQ-INV-016/-017)

4. Die eigene Blaupausen-Liste öffnen und eine automatisch vergebene Standard-Blaupause auswählen.
5. `/admin/default-blueprints` öffnen und einen Eintrag über das Bestätigungsmodal entfernen.

## Erwartetes Ergebnis

- **Beschreibung:** Beide Speichervorgänge zeigen einen Erfolgs-Toast ohne Reload; der zweite gelingt trotz verfälschtem Token über ein transparentes `GET /csrf` und genau einen Wiederholungsversuch (`krtCsrf.refresh()`).
- **Präferenz und Freigabe:** Beide Speichervorgänge zeigen einen Erfolgs-Toast, keinen Fehler- oder Konflikt-Toast und keinen Reload-Bestätigungsdialog; der Marker überlebt, und das Backend hält den zweiten Wert.
- **Standard-Blaupausen:** Der Detailbereich bietet Bearbeiten an, aber **kein** Löschen (`removable=false`). Die Admin-Seite listet den geseedeten Satz, und nach dem Entfernen ist er um einen Eintrag kleiner.

## Sonderfälle & Lehren

- **Die Klasse veralteter Versionen** (epic #571): Das Backend gab nach einer echten Änderung eine alte Nutzer-`version` zurück, der nächste Schreibvorgang lief auf 409 (`OPTIMISTIC_LOCK`). Der Fix (`save` → `saveAndFlush`) liefert die frische Version, die die Seite über `syncAllVersions` zurückschreibt. Jeder Speichervorgang muss den Wert wirklich ändern — sonst erhöht sich die `@Version` nicht, und der Test prüfte nichts.
- **Verstecktes Löschen statt 409.** Dass eine Standard-Blaupause nicht entfernbar ist, zeigt die Oberfläche durch das fehlende Löschen-Element — die Lösung, die der Eigentümer einer Fehlermeldung vorgezogen hat.
- **Die Tests sind reihenfolgeunabhängig:** Das Entfernen aus dem Standard-Satz nimmt Nutzern keine schon vergebenen Blaupausen weg.
