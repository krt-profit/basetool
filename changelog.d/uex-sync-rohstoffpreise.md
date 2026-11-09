### Fixed

- **Rohstoffpreise.** Der UEX-Abgleich übernimmt die Rohstoffpreise wieder. SCU-Mengen über rund
  2,1 Milliarden ließen den ganzen Preisabruf und damit den Job `uex_sync` scheitern; sie werden
  jetzt vollständig gespeichert (Migration V375).
