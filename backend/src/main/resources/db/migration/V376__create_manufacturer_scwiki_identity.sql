-- Every SC Wiki manufacturer row that resolves to a local manufacturer: the primary link
-- (manufacturer.scwiki_uuid) and the Wiki duplicates of the same company (ADR-0356, REQ-DATA-033).
CREATE TABLE manufacturer_scwiki_identity (
    scwiki_uuid     UUID PRIMARY KEY,
    manufacturer_id UUID         NOT NULL REFERENCES manufacturer (id) ON DELETE CASCADE,
    wiki_name       VARCHAR(255) NOT NULL,
    wiki_code       VARCHAR(64),
    synced_at       TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_manufacturer_scwiki_identity_manufacturer
    ON manufacturer_scwiki_identity (manufacturer_id);

INSERT INTO manufacturer_scwiki_identity (scwiki_uuid, manufacturer_id, wiki_name, wiki_code, synced_at)
SELECT m.scwiki_uuid, m.id, m.name, m.scwiki_code, COALESCE(m.scwiki_synced_at, now())
FROM manufacturer m
WHERE m.scwiki_uuid IS NOT NULL;
