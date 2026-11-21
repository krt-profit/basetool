# UC-51 — Navigation: Menü, Schnellzugriff, Personalisierung & Tastenkürzel

|                |        |
|----------------|--------|
| **ID**         | UC-51  |
| **Tag**        | `e2e`  |
| **Testklasse** | [`NavigationE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/NavigationE2eTest.java) · [`QuickAccessSearchE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/QuickAccessSearchE2eTest.java) · [`NavigationPersonalisationE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/NavigationPersonalisationE2eTest.java) · [`NavigationOrderE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/NavigationOrderE2eTest.java) · [`NavigationTabBarE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/NavigationTabBarE2eTest.java) · [`NavigationStartPageE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/NavigationStartPageE2eTest.java) · [`NavigationDataTabGateE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/NavigationDataTabGateE2eTest.java) · [`NavigationShortcutsE2eTest`](../../frontend/src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e/NavigationShortcutsE2eTest.java) |
| **Spec**       | [`ui-design-system.md`](../specs/ui-design-system.md) (REQ-UI-026, REQ-UI-032, REQ-UI-033, REQ-UI-034, REQ-UI-035) |

## Akteur

- `test-admin` — Menü, Administrationsmodus, Schnellzugriff mit Entitätssuche.
- `test-member` — Favoriten, ausgeblendete Seiten, Reihenfolge, Tab-Leiste und Startseite; als einfaches Mitglied auch die Gegenprobe der Startseiten-Auswahl.
- `test-admin` als Handelsbeauftragter (`preparePurchasing`) — die Startseiten-Auswahl mit „Bericht".

## Vorbedingungen

- Alle Klassen brauchen den ephemeren Stack.
- **Schnellzugriff:** ein Einsatz, eine Operation, ein Material und ein Job Order mit bekannter Nummer in einer frischen Profit-Staffel.
- **Startseiten-Auswahl:** Bedarf- und Ankauf-Oberflächen eingeschaltet.
- Die Personalisierungs-Tests seeden nichts.

## Auslöser

Der Nutzer navigiert über Seitenleiste, Rail, Tab-Leiste oder den Schnellzugriff, richtet sein Menü unter „Anpassen" ein, wählt eine Startseite oder benutzt die Tastenkürzel.

## Hauptablauf

### Menü und Schnellzugriff (`NavigationE2eTest`, REQ-UI-026, REQ-UI-033)

1. Im Menü filtern und an den Schnellzugriff übergeben; den Schnellzugriff mit `Strg+K` öffnen und mit Escape schließen; tolerant suchen, Seitenaktionen ausführen, mit `Strg+Enter` in einem neuen Tab öffnen, mit Tab ein Präfix übernehmen und mit `Strg+D` einen Favoriten umschalten.
2. Eine Admin-Seite öffnen: das Menü steht im Administrationsmodus, die Admin-Gruppen starten eingeklappt hinter dem Hub-Link, zusammengeführte Admin-Seiten zeigen ihre Tab-Leiste.
3. Die Standardbelegung eines Mitglieds, alte Lager-URLs und die Tab-Leiste mit Menü-Sheet auf dem Smartphone prüfen.
4. Die Desktop-Modi je Breite durchgehen, den Seitenleisten-Schalter umlegen, die Flyouts der Rail per Hover, Klick und Tastatur öffnen, „Anpassen" aus der Rail öffnen und den Avatar-Flyout benutzen.

### Entitätssuche (`QuickAccessSearchE2eTest`, REQ-UI-033)

5. Im Schnellzugriff nach Einsätzen, Operationen, Materialien und einem Job Order über seine Nummer suchen, mit Präfixen die Art einengen und späte Treffer abwarten.

### Favoriten und ausgeblendete Seiten (`NavigationPersonalisationE2eTest`, REQ-UI-032)

6. Den Stern im Seitenkopf setzen und lösen, bis zur Obergrenze von zwölf; Seiten ausblenden und zurücksetzen; aus einem zweiten, veralteten Fenster speichern.

### Reihenfolge ohne Ziehen (`NavigationOrderE2eTest`, REQ-UI-032)

7. In „Anpassen" Gruppen, Links und Favoriten mit den Hoch-/Runter-Knöpfen und mit Alt+Pfeil verschieben, neu laden und „Standardreihenfolge" auslösen; eine Rücksetzung und ein Entsternen hinter einer noch laufenden Verschiebung einreihen; aus einem veralteten Fenster verschieben.

### Tab-Leiste des Smartphones (`NavigationTabBarE2eTest`, REQ-UI-032)

8. In 375×812 die automatisch gefüllte Leiste bearbeiten, einen belegten Eintrag wählen, Nachbarn tauschen, per langem Druck den Picker öffnen und zurücksetzen; als Administrator beide Modi ansehen.

### Startseite und vollständiges Zurücksetzen (`NavigationStartPageE2eTest`, `NavigationDataTabGateE2eTest`, REQ-UI-032, REQ-UI-034)

9. Über den Knopf im Seitenkopf und über die Auswahl im Profil eine Startseite setzen und lösen und `/start` aufrufen; aus einem veralteten Fenster speichern; das vollständige Zurücksetzen auslösen.
10. Die Startseiten-Auswahl im Profil als einfaches Mitglied und als Handelsbeauftragter öffnen.

### Tastenkürzel (`NavigationShortcutsE2eTest`, REQ-UI-035)

11. Alt+1, Alt+2 und Alt+0 drücken, auch auf einem leeren oder ausgegrauten Platz, bei offenem Dialog, im Administrationsmodus und mit ausgeschaltetem Profil-Schalter; den Dialog „Tastenkürzel" aus dem Profilmenü und dem Schnellzugriff öffnen.

## Erwartetes Ergebnis

- **Menü und Schnellzugriff:** Der Filter engt das Menü ein und übergibt den Begriff; der Schnellzugriff schließt mit Escape, zeigt im Leerzustand häufige Seiten ohne Dubletten, ausgeblendete Seiten verlassen ihn, ihre Treffer bleiben, und eine Seitenaktion navigiert nur. Die Desktop-Modi folgen ihren Breiten und behalten den gespeicherten Modus; Flyouts schließen mit Escape.
- **Entitätssuche:** Alle Arten stehen mit den Seiten in einer Liste, Entitäten landen nicht in den zuletzt benutzten, Präfixe engen die Art ein, und die Hervorhebung überlebt späte Treffer.
- **Favoriten:** Stern, Gruppe „Favoriten" und „Mehr" folgen in place; bei zwölf ist der Stern deaktiviert; Favorit und ausgeblendet schließen sich in einem Schreibvorgang aus; persönliche Seiten haben kein Ausblenden; ein veraltetes Fenster lädt nur die Favoriten neu bzw. verwirft sein Ausblenden und sagt es an.
- **Reihenfolge:** Das verschobene Element behält den Fokus, eine Live-Region sagt die Verschiebung an, die Enden einer Gruppe bieten keine weitere Verschiebung, Alt+Ziffer folgt der Reihenfolge, die Reihenfolge überlebt den Reload, und eine eingereihte Aktion trägt die Version der Verschiebung.
- **Tab-Leiste:** Die erste Bearbeitung friert die automatische Füllung ein, ein belegter Eintrag tauscht die Plätze, ein kurzer Druck navigiert weiter, und ein Administrator hat je Modus eine eigene Leiste.
- **Startseite:** Knopf und Auswahl setzen die Startseite in place, `/start` folgt ihr, und das Dashboard bleibt erreichbar; das vollständige Zurücksetzen leert jeden Abschnitt, beide Dashboards und beide Startseiten in place. „Bericht" wird nur dem Handelsbeauftragten angeboten.
- **Tastenkürzel:** Alt+1 öffnet Favorit 1, Alt+0 das Dashboard, nur die Ziffernreihe zählt; ein leerer oder ausgegrauter Platz meldet sich per Toast, und die Tasten ruhen bei offenem Dialog, im Administrationsmodus und bei ausgeschaltetem Schalter. Der Dialog nennt den Mitgliedermodus nur im Administrationsmodus und verweist auf den Schalter, solange er aus ist.
- Die Bedienelemente von „Anpassen" und der Tab-Leiste erreichen die Touch-Untergrenze (REQ-UI-009).

## Sonderfälle & Lehren

- **Versionen statt letzter Schreiber.** Favoriten, ausgeblendete Seiten, Reihenfolge und Startseite tragen eine Version; ein veraltetes Fenster überschreibt nichts still, sondern verwirft seine Änderung und sagt es an.
