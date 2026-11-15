### Fixed

- **Einkauf und Bank.** Wird ein Deal storniert, während die Bank über einen seiner Anträge entscheidet, blockieren sich die beiden nicht mehr gegenseitig; sie laufen nacheinander durch.
- **Bank-Reset.** Eine gebührenfreie Umbuchung zwischen Verwahrern, die gleichzeitig mit dem Wipe-Reset gebucht wird, wartet auf ihn, sodass der Reset die Bestände genau auf null setzt.
