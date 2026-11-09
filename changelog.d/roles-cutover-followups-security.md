### Security

- **Weniger Rechte für das Admin-API-Konto des Backends.** Der Realm-Provisioner entzieht dem
  Dienstkonto von `backend-service` die nicht mehr benötigte Keycloak-Rolle `view-realm`.
