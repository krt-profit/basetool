### Fixed

- **Staffel bearbeiten nach Bereichszuordnung.** Wurde eine Staffel einem Bereich zugeordnet, meldete
  das Speichern unter Admin → Staffeln fälschlich „Konflikt: Jemand war schneller“, weil die
  Staffelliste aus dem Cache eine veraltete Version lieferte. Die Zuordnung leert den Cache jetzt.
