### Changed

- **Bankhalter folgen Rollenänderungen sofort.** Wer im Basetool die Rolle Bank-Mitarbeiter oder
  Bank-Management erhält oder verliert, wird gleich danach als Halter angelegt, reaktiviert oder
  deaktiviert, nicht erst beim nächsten nächtlichen Abgleich.
- **Grafana-Zugang über eine eigene Keycloak-Rolle.** Grafana lässt nur noch Konten mit der
  Client-Rolle `grafana-admin` am Keycloak-Client `grafana` herein; die Basetool-Rolle Admin genügt
  dafür nicht mehr.
