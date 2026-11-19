### Changed

- **Plattform.** Die drei Anwendungen laufen auf Spring Boot 4.2 (vorerst 4.2.0-RC2, die finale
  4.2.0 folgt). Der Standard-Header `Forwarded` wird jetzt ignoriert; ausgewertet werden nur die
  `X-Forwarded-*`-Header des Proxys.
