-- Stufe-2-Backstop fuer die "ein Teilnehmer sitzt in hoechstens einer Crew"-Regel
-- (Issue #1132, Epic #1109). Der In-Memory-anyMatch in
-- MissionStructureService.addCrewToShip deckt den Normalfall ab, hat aber einen
-- TOCTOU-Race: zwei Manager ziehen denselben unzugewiesenen Teilnehmer gleichzeitig
-- auf zwei verschiedene Einheiten. Beide sehen einen Pre-Insert-Snapshot, beide
-- bestehen den Check, beide INSERTen eine neue mission_crew-Zeile (verschiedene Zeilen,
-- kein @Version-Konflikt) -> der Teilnehmer sitzt auf zwei Schiffen.
--
-- Wie beim Teilnehmer-Anmelde-Race (V96) ist der Datenbank-seitige Backstop ein
-- UNIQUE-Index. Da eine Teilnehmerzeile zu genau einem Einsatz gehoert, genuegt der
-- Ein-Spalten-Index auf mission_participant_id (NOT NULL seit V1), um die
-- Ein-Crew-je-Teilnehmer-Invariante einsatzweit zu erzwingen. Der geraced zweite INSERT
-- faellt dann als DataIntegrityViolationException -> HTTP 409 (GlobalExceptionHandler),
-- gleicher Status wie der freundliche In-Memory-Zweig. Der anyMatch bleibt als
-- lokaler Fast-Path erhalten.

-- Backfill zuerst: falls heute schon Dubletten existieren, die aelteste Crew-Zeile je
-- mission_participant_id behalten und die uebrigen loeschen (nur die Crew-Zuordnung,
-- der Teilnehmer bleibt). mission_crew_job_types haengt per FK an mission_crew und muss
-- zuerst geraeumt werden.
CREATE TEMP TABLE _v207_dup_crew ON COMMIT DROP AS
SELECT id
FROM (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY mission_participant_id
               ORDER BY created_at NULLS LAST, id
           ) AS rn
    FROM mission_crew
) ranked
WHERE rn > 1;

DELETE FROM mission_crew_job_types
WHERE mission_crew_id IN (SELECT id FROM _v207_dup_crew);

DELETE FROM mission_crew
WHERE id IN (SELECT id FROM _v207_dup_crew);

CREATE UNIQUE INDEX IF NOT EXISTS uq_mission_crew_participant
    ON mission_crew (mission_participant_id);
