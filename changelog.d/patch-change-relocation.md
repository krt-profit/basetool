### Added

- **Patchwechsel: neue Home Location.** Fragt ein Patchwechsel nach der Home Location, wählt jedes Mitglied, dessen Konto vor dem Live-Gang bestand, beim nächsten Aufruf seine neue Home Location; danach ziehen seine Lagerposten (eigene und für Einheiten gehaltene) und Schiffe von vor dem Live-Gang einmalig dorthin um, Zweckbindungen, Spenden und Angebote bleiben erhalten.
- **Umzugssperre.** Bis zur Wahl sind diese Posten und Schiffe für andere gesperrt: Lager und Hangar zeigen sie als „Umzug ausstehend“ mit gesperrten Aktionen, verbundene Anwendungen erhalten `409 RELOCATION_PENDING`, neue Buchungen bleiben möglich.
- **Admin.** Admins wählen im neuen Mitglieder-Reiter „Home Location“ für ein Mitglied, beenden eine laufende Abfrage in der Patchwechsel-Historie vorzeitig, und die Kennzahl `basetool_patch_change_relocations_pending` zeigt, wer noch umziehen muss.
