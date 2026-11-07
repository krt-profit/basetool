### Changed

- **P4K-Import: Blaupausen.** Rezept, Mengen samt Mengenfaktor, Wahlplätze, Mindestqualität,
  Herstellzeit, Werte-Modifikatoren, Kategorie, Standard-Freischaltung und Zerlegen kommen aus dem
  P4K; das SC Wiki füllt nur noch Lücken und meldet abweichende Werte im Sync-Bericht. Mit dem
  Häkchen „neue Items und Schiffe anlegen“ legt der Import auch Blaupausen an, deren Item es gibt
  (REQ-P4K-027, REQ-P4K-028).
- **Prüfliste: Rohstoff zuordnen.** Eine Rezept-Ressource ohne eindeutigen Rohstoff bekommt in der
  Prüfliste einen Rohstoff zugeordnet; die Zuordnung bleibt bei jedem weiteren Import erhalten
  (REQ-P4K-024).
- **Herstellbarkeit mit Wahlplätzen.** Muss man nur zwei von drei Plätzen füllen, zählt die
  Herstellbarkeit die zwei Plätze, die das eigene Lager am besten abdeckt; additive Werte stehen im
  Rezept als „+1“ statt als Prozentfaktor (REQ-INV-048, REQ-INV-069).
