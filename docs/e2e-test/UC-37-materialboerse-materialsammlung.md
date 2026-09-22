# UC-37 — Materialbörse & Materialsammlung eines Auftrags

|                |        |
|----------------|--------|
| **ID**         | UC-37  |
| **Tag**        | `e2e`  |
| **Testklasse** | [`MaterialboardItemStockOfferE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/MaterialboardItemStockOfferE2eTest.java) · [`MaterialboardOfferedAmountFieldE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/MaterialboardOfferedAmountFieldE2eTest.java) · [`MaterialboardPickerServerSearchE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/MaterialboardPickerServerSearchE2eTest.java) · [`MaterialboardQuantityFieldExclusivityE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/MaterialboardQuantityFieldExclusivityE2eTest.java) · [`MaterialboardReleaseModalOpensE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/MaterialboardReleaseModalOpensE2eTest.java) · [`MaterialboardRequestModalE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/MaterialboardRequestModalE2eTest.java) · [`MaterialCollectionDeliveredInPlaceE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/MaterialCollectionDeliveredInPlaceE2eTest.java) · [`MaterialCollectionTransferInPlaceE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/MaterialCollectionTransferInPlaceE2eTest.java) |
| **Spec**       | [`materialboerse.md`](../specs/materialboerse.md) (REQ-MARKET-002/-007/-012/-014/-015…) · [ADR-0086](../adr/0086-materialboerse-partial-offer-amount.md) · [ADR-0108](../adr/0108-materialboerse-stock-backed-item-offers.md) |

## Akteur

`test-admin` — seine geseedete IRIDIUM-Mitgliedschaft bringt `KRT_MEMBER`, die Rolle, die die Börse verlangt.

## Vorbedingungen

- **Item-Angebot:** ein bestellbares Spiel-Item (Ausgabe eines aktiven Bauplans, `seedOrderableItem`, REQ-INV-029) und ein eigener Item-Lagereintrag, angelegt über den echten `POST /api/v1/inventory` mit `gameItemId` (`createItemInventoryEntry`).
- **Materialsammlung:** ein Job Order und ein an ihn geknüpfter Lagereintrag.
- Die übrigen Börsen-Tests brauchen keinen Seed: die Dialoge öffnen mit leerem Picker, und genau dieser Zustand wird geprüft.

## Auslöser

Der Nutzer bietet auf `/materialboerse` Material oder ein Item an, sucht im Picker, wechselt zu den Gesuchen, oder er arbeitet die Materialsammlung eines Auftrags ab.

## Hauptablauf

### Materialbörse

1. **Item aus dem Lager anbieten** (REQ-MARKET-014): „Material anbieten" öffnen, im Picker die Item-Zeile wählen, 5 Stück anbieten, absenden (`/materialboerse/offers/ajax`).
2. **Mengenfeld** (REQ-MARKET-002, ADR-0086): „Material anbieten" im Neu-Modus öffnen.
3. **Serverseitige Suche**: im Picker tippen.
4. **Genau ein Mengenfeld**: erst „Material anbieten", dann „Item anbieten" öffnen.
5. **Modal wird sichtbar** (REQ-MARKET-007): Seite laden, „Material anbieten" klicken, in das Picker-Feld klicken, Modal schließen.
6. **Gesuche** (REQ-MARKET-015…): auf den Tab „Alle Gesuche" wechseln, „Material suchen" klicken, den Picker anklicken, zwischen Material und Item umschalten.

### Materialsammlung eines Auftrags (#577)

7. **Geliefert zweimal umschalten**: auf `/orders/{id}/material-collection` den Reload-Marker setzen und die Checkbox „geliefert" derselben Zeile zweimal hintereinander umschalten (`PATCH /inventory/{id}/delivered`).
8. **Umbuchen, dann umschalten**: den Ort der Zeile über `.location-select` ändern (voller Transfer, `POST /inventory/{id}/transfer`), dann ohne Reload in derselben Zeile „geliefert" umschalten.

## Erwartetes Ergebnis

- Das Item-Angebot steht mit Item-Namen und der Art-Marke „Item" auf der Börse (ein lagergedecktes Item-Angebot, ADR-0108).
- Das Feld „Menge anbieten" ist sichtbar, aber **deaktiviert**, bis ein Posten gewählt ist (dann erhält es dessen Bestand als `max`).
- Das Tippen löst eine neue Anfrage `/materialboerse/releasable-items?q=…` aus — nicht nur einen Client-Filter.
- Beim Material-Angebot ist nur „Menge anbieten" sichtbar, beim Item-Angebot nur „Menge (Stück)".
- Das Modal ist beim Laden versteckt, nach dem Klick **wirklich sichtbar** und nach dem Schließen wieder weg; die Picker-Liste ist beim Öffnen zu und klappt erst beim Klick auf.
- Der Gesuche-Tab benennt die Buttons um („Material suchen" / „Item suchen", die Angebots-Buttons verschwinden); das Anfrage-Modal öffnet sichtbar, der Art-Schalter tauscht Material- gegen Bauplan-Combobox, und Mindestqualität und Wunschmenge gibt es für beide Arten.
- Beide Umschaltvorgänge der Materialsammlung zeigen einen Erfolgs-Toast, keinen Fehler-Toast, keinen Reload; das Backend (`GET /api/v1/orders/{id}/material-collection`) hält den zweiten Wert. Nach dem Transfer landet das Umschalten ohne `404`.

## Sonderfälle & Lehren

- **Nur ein echter Browser sieht CSS und JS.** Drei der Börsen-Fehler waren für MockMvc unsichtbar: das deaktivierte Mengenfeld hängt an `materialboerse-release.js`; das zweite Mengenfeld blieb sichtbar, weil die Autorenregel `display:flex` von `.mb-modal-qty` die UA-Regel `[hidden]` schlug (Fix: `.mb-modal-qty[hidden]`); das Modal öffnete unsichtbar, weil das Entfernen des `hidden`-Attributs das CSS-`display:none` des `.krt-modal-overlay` nicht aufhob.
- **Der Picker filterte im Client.** Die Backend-Abfrage (`findReleasableForUser`) liefert höchstens 50 Zeilen, alphabetisch. Der erste Stand lud diese Liste einmal und filterte clientseitig — ein Material spät im Alphabet tauchte nie auf, und die Freigabe lief still ins Leere. Der Test braucht keine 50 Einträge: dass die Anfrage an den Server geht, sichert die Erreichbarkeit.
- **Veraltete `@Version` nach In-Place-Schreiben** (epic #571): Das Backend gab nach dem ersten Umschalten eine alte Version zurück, der zweite Schreibvorgang lief auf 409 (`OPTIMISTIC_LOCK`). Der Fix (`save` → `saveAndFlush`) liefert die frische Version, die die Seite auf `data-version` der Zeile zurückschreibt.
- **Veraltete Zeilen-Identität nach Transfer:** Ein voller Transfer löscht den Quelleintrag und hängt einen neuen Zieleintrag an (eigene Id und Version) und gibt dessen DTO zurück. Der alte Handler erwartete ein `204` ohne Body — die Zeile behielt die Id des gelöschten Eintrags, und das folgende Umschalten lief auf `404`.
