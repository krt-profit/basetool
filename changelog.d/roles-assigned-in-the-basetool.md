### Added

- **Rollen werden im Basetool vergeben.** Admins vergeben und entziehen Admin, KRT Member, Bank Employee und Bank Management auf der Mitgliederseite (Reiter *Rollen*), die Bankleitung vergibt Bank Employee unter *Bank-Mitarbeiter*. Jede Änderung wird protokolliert und dem Mitglied mitgeteilt; der letzte aktive Admin behält seine Rolle.
- **Notfallzugang für Admins.** `APP_BOOTSTRAP_ADMIN_SUBS` nennt Keycloak-Subjects, die beim Login die Admin-Rolle erhalten, solange kein aktiver Admin existiert.
