### Fixed

- **Überwachung.** Der podman-Exporter liefert seine Image-Metriken wieder ohne Fehler. Zeigt ein
  neuer Image-Digest auf ein Image, das der Server schon hat, behält das Image nach dem Deploy und
  der wöchentlichen Bereinigung nur noch einen Namen statt zweier, die der Exporter doppelt meldete.
