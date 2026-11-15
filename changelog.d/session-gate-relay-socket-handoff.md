### Fixed

- **Zustimmung und Home Location.** Auch Speichern per AJAX, Formular-Rückfälle und parallel geladene Seitenteile führen beim ersten abgewiesenen Aufruf sofort zur Zustimmung bzw. zur Abfrage der Home Location, und eine neue Live-Sync-Verbindung prüft beide Schritte frisch, statt einer bis zu einer Minute alten Freigabe zu vertrauen.
- **Profil: Meine Home Location.** Kann die gespeicherte Home Location nicht gelesen werden, zeigt der Abschnitt das an und bietet kein Formular, statt „keine Wahl“ anzuzeigen und beim Speichern einen Konflikt zu melden.
