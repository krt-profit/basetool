### Fixed

- **Rohstoffpreise.** Der stündliche UEX-Abgleich läuft wieder vollständig durch. UEX führt an
  20 Orten ein Gegenstands- und ein Rohstoff-Terminal mit gleichem Namen; beide bekommen jetzt
  eine eigene Zeile, sodass jeder Preis sein Terminal findet und veraltete Preise wieder
  aufgeräumt werden.

- **SC-Wiki-Abgleich.** Rohstoffe, Baupläne, Hersteller und Gegenstände werden wieder vollständig
  übernommen: Die Rohstoff-Stufe kommt als Seltenheit (gewöhnlich bis legendär), leere Wiki-Felder
  brechen keine Bauplan-Details mehr ab, Hersteller mit geteiltem Namen oder Kürzel gelten nicht
  mehr als mehrdeutig, und eine Liste, die sich beim Abruf verschiebt, wird einmal neu gelesen.
