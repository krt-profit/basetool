ALTER TABLE announcement ADD COLUMN singleton BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE announcement SET singleton = TRUE
WHERE id = (
  SELECT id FROM announcement
  ORDER BY CASE WHEN content IS NOT NULL AND btrim(content) <> '' THEN 0 ELSE 1 END,
    updated_at DESC NULLS LAST, id
  LIMIT 1
);

CREATE UNIQUE INDEX uq_announcement_singleton ON announcement (singleton) WHERE singleton;
