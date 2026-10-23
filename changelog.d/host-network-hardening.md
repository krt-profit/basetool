### Security

- **Server-Netzwerk.** Der Server nimmt keine umgeleiteten Netzwerkrouten (ICMP-Redirects) mehr an
  und verwirft gefälschte Absender. Die Firewall schließt einen ungenutzten Dienst. Wer sich
  wiederholt falsch per SSH anmeldet, wird schneller gesperrt und bei Wiederholung eine Woche lang
  auf allen Ports (REQ-OPS-053).
