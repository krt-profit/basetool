-- Stufe-2-Backstop fuer die "Ein Einsatzleiter pro Einsatz"-Regel (REQ-MISSION-013,
-- Issue #1113, Epic #1109). Der In-Memory-anyMatch in
-- MissionParticipantService.updateParticipantAttributes deckt den Normalfall ab, hat
-- aber einen klassischen TOCTOU-Race: zwei Manager weisen gleichzeitig zwei
-- VERSCHIEDENEN Teilnehmern desselben Einsatzes die Einsatzleiter-Rolle zu. Beide
-- sehen einen "noch kein Leiter"-Snapshot, beide bestehen den Check, beide schreiben
-- in unterschiedliche Zeilen (jede mit eigenem @Version) -> nichts serialisiert die
-- Writes, es entstehen zwei Einsatzleiter.
--
-- Dieser Index ist der Datenbank-seitige Backstop (Muster von V96 / V200): ein
-- abgeleitetes Boolean-Feld is_mission_lead_participant (true genau dann, wenn der
-- geplante Job-Type der Teilnehmerzeile der Einsatzleiter-Typ ist) plus ein PARTIELLER
-- UNIQUE-Index auf (mission_id) WHERE is_mission_lead_participant. Der Service haelt das
-- Feld an der einzigen Schreibstelle synchron; JobTypeService loescht es, wenn ein
-- Job-Type die Einsatzleiter-Designation verliert. Der geraced zweite Write faellt dann
-- am Index als DataIntegrityViolationException -> HTTP 409 (GlobalExceptionHandler),
-- gleicher Status wie der freundliche In-Memory-Zweig.

-- 1) Neue Spalte (NOT NULL, Default false) - erfuellt zugleich ddl-auto=validate fuer
--    das neue Entity-Feld MissionParticipant.missionLeadParticipant.
ALTER TABLE mission_participant
    ADD COLUMN is_mission_lead_participant BOOLEAN NOT NULL DEFAULT FALSE;

-- 2) Backfill aus dem aktuellen geplanten Job-Type: Einsatzleiter = geplanter Job-Type
--    ist als is_mission_lead markiert.
UPDATE mission_participant mp
SET is_mission_lead_participant = TRUE
FROM job_type jt
WHERE mp.planned_task_job_type_id = jt.id
  AND jt.is_mission_lead = TRUE;

-- 3) Bestehende Verletzungen (Pre-Policy-Daten mit >1 Einsatzleiter pro Einsatz)
--    aufloesen: aelteste Zeile je Einsatz behalten, die uebrigen degradieren
--    (Flag loeschen UND geplanten Einsatzleiter-Job-Type entfernen, damit Flag und
--    Job-Type konsistent bleiben). Die Teilnehmer selbst bleiben erhalten.
CREATE TEMP TABLE _v206_dup_leads ON COMMIT DROP AS
SELECT id
FROM (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY mission_id
               ORDER BY created_at NULLS LAST, id
           ) AS rn
    FROM mission_participant
    WHERE is_mission_lead_participant
) ranked
WHERE rn > 1;

UPDATE mission_participant
SET is_mission_lead_participant = FALSE,
    planned_task_job_type_id = NULL
WHERE id IN (SELECT id FROM _v206_dup_leads);

-- 4) Partieller UNIQUE-Index: hoechstens eine Einsatzleiter-Zeile je Einsatz.
CREATE UNIQUE INDEX IF NOT EXISTS uq_mission_participant_single_lead
    ON mission_participant (mission_id)
    WHERE is_mission_lead_participant;
