### Changed

- **Server.** skopeo, mit dem der Deploy-Lauf Images auflöst und sich an der Registry anmeldet, wird
  nicht mehr unbeaufsichtigt aktualisiert, sondern zusammen mit Podman in der geplanten Wartung,
  zuerst auf dem Testhost. So bleiben beide in einer Version, die zueinander passt.
