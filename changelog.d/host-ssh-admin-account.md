### Security

- **Server-Zugang.** Auf Test- und Produktionsserver meldet sich der Betrieb mit einem persönlichen
  Konto per SSH-Schlüssel an, dessen `sudo` ein Passwort verlangt. `root` kann sich nicht mehr per
  SSH anmelden. Die Ansible-Rolle sperrt `root` erst in einem Lauf, der das neue Konto selbst
  benutzt hat (REQ-OPS-052).
