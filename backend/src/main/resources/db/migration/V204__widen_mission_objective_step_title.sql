-- Widen the Ziele (goal) and Ablauf (step) title columns from 250 / 200 to 500 chars (owner
-- request). The entity @Column length and the request-DTO @Size caps both move to 500, so with
-- ddl-auto=validate the DB column must hold the longer titles. Increasing a VARCHAR length limit is
-- a metadata-only change in PostgreSQL (no table rewrite, no scan). The mission description stays a
-- TEXT column (already unbounded), so only these two short-title columns grow.
ALTER TABLE mission_objective ALTER COLUMN title TYPE VARCHAR(500);
ALTER TABLE mission_step ALTER COLUMN title TYPE VARCHAR(500);
