### Fixed

- **Lager-Zuordnungen lassen das Material unberührt.** Eine Zuordnung eines Lagerpostens zu einem Auftrag oder Einsatz und das Setzen von „Geliefert“ erhöhen nur noch die Version des Postens, nicht mehr die seines Materials. Eine gleichzeitige Material-Bearbeitung bekommt daher keinen falschen Versionskonflikt mehr, und ein Material ohne Version lässt diese Schreibvorgänge nicht mehr scheitern (REQ-INV-027).
