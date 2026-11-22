### Fixed

- **Überwachung.** Der podman-Exporter übernimmt neue und entfernte Images sofort statt erst bei
  seinem nächsten Neustart. Podman schreibt seine Ereignisse für den Dienstbenutzer dafür in eine
  Datei, die der Exporter lesen kann; das Journal des Systems war für ihn nicht lesbar.
