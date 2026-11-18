### Fixed

- **Server.** Der Deploy-Lauf meldet sich für sein eigenes Registry-Login über skopeo statt über
  Podman an. So bleibt keine Podman-Sperrdatei zurück, deren Löschung SELinux bei jedem Lauf
  verweigert und protokolliert.
