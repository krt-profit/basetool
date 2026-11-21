# UC-53 — Ankauf: Partner, Deal, Bankanfrage & Forumsbericht

|                |        |
|----------------|--------|
| **ID**         | UC-53  |
| **Tag**        | `e2e`  |
| **Testklasse** | [`PurchasingDealFlowE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/PurchasingDealFlowE2eTest.java) · [`PurchasingReportForumCopyE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/PurchasingReportForumCopyE2eTest.java) |
| **Spec**       | [`purchasing.md`](../specs/purchasing.md) (REQ-PUR-004/-007/-012/-022/-023) · [`security-and-access.md`](../specs/security-and-access.md) (REQ-SEC-064) |

## Akteur

`test-admin` als Handelsbeauftragter.

## Vorbedingungen

- Beide Klassen brauchen den ephemeren Stack.
- Die IRIDIUM-Mitgliedschaft und `BackendSeeder.preparePurchasing`: IRIDIUM darf Bedarfe anlegen, ein Finanzierungskonto und die Ankauf-Einstellungen bestehen, der Nutzer hält eine Bankkonto-Rolle und ist als Beauftragter ernannt, und es gibt einen ankaufbaren Bedarf.

## Auslöser

Der Beauftragte legt einen Ankauf an oder kopiert den Bericht für das Forum.

## Hauptablauf

### Deal bis zur Bankanfrage (`PurchasingDealFlowE2eTest`, REQ-PUR-004/-007/-012/-022)

1. Auf `/purchasing/partners` einen Partner anlegen.
2. Auf `/purchasing/deals` für den ankaufbaren Bedarf einen Deal anlegen, die erste Tranche bei der Bank anfragen und den Deal wieder stornieren.

### Forumstext in die Zwischenablage (`PurchasingReportForumCopyE2eTest`, REQ-PUR-023, REQ-SEC-064)

3. Auf `/purchasing/report` den Kopieren-Knopf des Forumstexts drücken.

## Erwartetes Ergebnis

- **Deal:** Jede Seite aktualisiert sich in place, ohne Reload.
- **Bericht:** Der Schreibvorgang über die Clipboard-API gelingt, und der Forumstext liegt in der Zwischenablage.

## Sonderfälle & Lehren

- **Die Zwischenablage hängt an der `Permissions-Policy`.** Das Frontend erlaubt `clipboard-write` für die eigene Origin; ohne diese Freigabe schlüge der Schreibvorgang im Browser fehl, obwohl der Knopf rendert.
