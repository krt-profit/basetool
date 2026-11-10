### Fixed

- **Materialbörse.** Ein Angebot, das freigegeben wird, während seine Lagerzeile gelöscht wird
  (Globales Lager leeren, Sammel-Ausbuchen, Massen-Umbuchen, Kontolöschung), verschwindet nicht
  mehr unprotokolliert: Es steht als entfernt im Audit-Log, oder die Freigabe wird abgelehnt, weil
  die Zeile nicht mehr existiert. Ein dabei zurückgezogenes Angebot meldet Interessenten nicht ein
  zweites Mal als weg.
