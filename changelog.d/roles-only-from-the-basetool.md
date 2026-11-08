### Changed

- **Rollen kommen nur noch aus dem Basetool.** Backend und Oberfläche lesen Admin, KRT Member, Bank Employee und Bank Management ausschließlich aus der Basetool-Datenbank; Login und täglicher Keycloak-Abgleich schreiben keine Rollen mehr. Eine Rollenänderung wirkt im Backend sofort, in der Oberfläche spätestens nach einer Minute.
- **Freigabe einer Registrierung vergibt KRT Member.** Neue Konten erhalten die Rolle nicht mehr automatisch aus Keycloak.
- **Keycloak-Realm ohne Anwendungsrollen.** Der Provisioner löscht die vier Realm-Rollen und nimmt der Web-Anmeldung den Rollen-Claim; `scripts/restore-keycloak-realm-roles.py` stellt sie für einen Rollback aus der Datenbank wieder her. `APP_SECURITY_PARTIAL_ROLE_SCOPE_CLIENT_IDS` entfällt.
