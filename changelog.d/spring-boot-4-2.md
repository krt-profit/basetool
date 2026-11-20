### Changed

- **Plattform.** Die drei Anwendungen laufen auf Spring Boot 4.2. Der Standard-Header `Forwarded` wird
  jetzt ignoriert; ausgewertet werden nur die `X-Forwarded-*`-Header des Proxys.
