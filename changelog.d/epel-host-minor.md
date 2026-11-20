### Fixed

- **Server.** Pakete aus EPEL kommen wieder aus dem Zweig für die Rocky-Version des Servers, nicht
  aus dem CentOS-Stream-Zweig, der ihr eine Version voraus ist. Der ungenutzte Mailserver `exim`,
  den fail2ban mitgebracht hatte, wird entfernt.
