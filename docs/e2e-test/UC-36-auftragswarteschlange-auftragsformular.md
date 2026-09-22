# UC-36 — Auftragswarteschlange & Auftragsformular: Live-Sync, Filter, Reaktivierung, Zeilen-Rendering

|                |        |
|----------------|--------|
| **ID**         | UC-36  |
| **Tag**        | `e2e`  |
| **Testklasse** | [`JobOrderQueueLiveSyncE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/JobOrderQueueLiveSyncE2eTest.java) · [`JobOrderReactivatePriorityInPlaceE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/JobOrderReactivatePriorityInPlaceE2eTest.java) · [`OrdersSquadronFilterE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/OrdersSquadronFilterE2eTest.java) · [`OrdersCreateItemLineRendersE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/OrdersCreateItemLineRendersE2eTest.java) · [`OrdersCreateScuHintRevealE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/OrdersCreateScuHintRevealE2eTest.java) |
| **Basis-Flow** | [UC-03](UC-03-job-order-anlegen.md), [UC-16](UC-16-job-order-status.md) |

## Akteur

`test-admin` (ADMIN, IRIDIUM-Mitglied). Der Live-Sync-Test nutzt zwei Browser-Kontexte desselben Nutzers.

## Vorbedingungen

- IRIDIUM-Mitgliedschaft und ein Job-Order-Material.
- **Reaktivieren:** ein per REST geseedeter offener Auftrag.
- **Staffel-Filter:** ein offener Auftrag mit IRIDIUM als bearbeitender Einheit. IRIDIUM wird genommen, weil die Filterliste aus dem Staffel-Katalog-Cache des Frontends (2 Stunden TTL) kommt und eine mitten im Lauf angelegte Staffel dort nicht erscheint.
- **Formular-Tests:** keine — sie bauen nur im Browser Zeilen und senden nie ab.

## Auslöser

Ein anderer Nutzer legt einen Auftrag an; ein abgeschlossener Auftrag wird wieder aufgenommen; der Nutzer filtert die Warteschlange oder baut im Anlegeformular Zeilen.

## Hauptablauf

### Warteschlange live (`JobOrderQueueLiveSyncE2eTest`, #1102)

1. Kontext B öffnet `/orders` und schaut nur zu. Kontext A füllt das echte Formular `/orders/create` aus und sendet es ab.

### Reaktivieren aktualisiert die Priorität (`JobOrderReactivatePriorityInPlaceE2eTest`, #575)

2. Auf `/orders/{id}` den Status auf `COMPLETED` setzen und im Warn-Modal bestätigen (das nullt die Priorität); neu laden, um die neue `@Version` zu holen.
3. Reload-Marker setzen und den Status auf `IN_PROGRESS` zurücksetzen.

### Staffel-Filter der Warteschlange (`OrdersSquadronFilterE2eTest`, REQ-ORDERS-027)

4. `/orders?status=OPEN&size=200` öffnen, im Multiselect-Filter IRIDIUM abwählen.
5. Die Seite neu laden.

### Zeilen im Anlegeformular (`OrdersCreateItemLineRendersE2eTest`, `OrdersCreateScuHintRevealE2eTest`)

6. `/orders/create` im Item-Modus öffnen und eine Item-Zeile hinzufügen.
7. `/orders/create` im Material-Modus öffnen, eine Materialzeile hinzufügen und ein SCU-Material wählen (gefunden über den Proxy `/catalog/material-search`, den auch die Combobox abfragt); danach die Combobox programmatisch mit `setValue('')` leeren.

## Erwartetes Ergebnis

- **Live:** B bekommt die neue Zeile **ohne Reload**. Die Prüfung hat einen großzügigen Timeout, weil der globale Raum `orders` Änderungen etwa 1,5 s lang sammelt.
- **Reaktivieren:** Der Header wird in place neu geholt (die Anfrage `GET /orders/{id}?fragment=header` tritt auf), der Marker überlebt, und das Backend hat eine neue, nicht leere Priorität vergeben.
- **Filter:** Der IRIDIUM-Auftrag verschwindet aus der Warteschlange; nach dem Reload ist die Abwahl aus `localStorage` wiederhergestellt und angewendet, ohne Zutun.
- **Formular:** Die clientseitig gebaute Item-Zeile (`oc-line-fields`) ist mit Flex-Layout sichtbar; beim SCU-Material erscheint der „?"-SCU-Hinweis der Zeile. In keinem der beiden Fälle protokolliert der Browser eine CSP-Verletzung `style-src-attr`. Die Option `data-quantity-type` wird auf das Hidden-Input der Combobox gespiegelt und nach `setValue('')` wieder entfernt (REQ-FE-016).

## Sonderfälle & Lehren

- **Der Server veröffentlicht, nicht der Ersteller** (REQ-FE-015, ADR-0094). Das Anlegen sendet `orders/[queue]` aus dem Frontend-`JobOrderWriteController`, weil der anlegende Kontext auf `/orders/create` steht und keinen Warteschlangen-Raum abonniert hat. Früher war der Ersteller ein anonymer Gast ohne Socket; seit ADR-0149 braucht Anlegen eine Anmeldung. Der Grund bleibt: eine Sitzung ist kein Socket. Ein Backend-Seed würde den Controller nie erreichen — deshalb das echte Formular.
- **Der Header-Tausch lag im falschen Zweig.** `_doStatusUpdate` tauschte `#order-header-results` nur im Zweig `COMPLETED`/`REJECTED`; beim Verlassen eines Endzustands blieb die Zelle „Priorität" bis zum Reload bei `-`, obwohl `JobOrderService.updateJobOrderStatus` serverseitig eine neue Priorität vergab.
- **Filter serverseitig, auf beiden Seiten.** Der frühere Umschalter „meine/alle" ist ein `localStorage`-gestützter Multiselect aller aktiven Staffeln; das Backend filtert auf bearbeitende **oder** anfragende Einheit. Die Abwesenheitsprüfung hängt an der `data-id` des Auftrags und gilt unabhängig von der Seitennummer.
- **CSP-Härtung, ADR-0093.** `orders-create.js` baute Zeilen per `innerHTML` mit Inline-`style=""`. Unter `style-src-attr 'none'` verlor die Zeile ihr Layout, und der SCU-Hinweis ließ sich nicht mehr ein- und ausblenden (das Leeren des leeren Inline-Stils schlug die Klassenregel nicht mehr). Der Fix nutzt `oc-*`-Klassen und schaltet `krtm-hidden`. Findet ein Stack kein SCU-Material, läuft der Konsolen-Wächter trotzdem, statt fälschlich zu scheitern.
