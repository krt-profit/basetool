CREATE TABLE quality_tier (
    id          UUID                     PRIMARY KEY,
    version     BIGINT                   NOT NULL DEFAULT 0,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    code        VARCHAR(32)              NOT NULL,
    min_quality INTEGER                  NOT NULL,
    label_de    VARCHAR(64)              NOT NULL,
    label_en    VARCHAR(64)              NOT NULL,
    sort_order  INTEGER                  NOT NULL DEFAULT 0,
    active      BOOLEAN                  NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_quality_tier_code UNIQUE (code),
    CONSTRAINT uq_quality_tier_min_quality UNIQUE (min_quality),
    CONSTRAINT ck_quality_tier_min_quality CHECK (min_quality BETWEEN 0 AND 1000),
    CONSTRAINT ck_quality_tier_code CHECK (code ~ '^[A-Z][A-Z0-9_]{0,31}$'),
    CONSTRAINT ck_quality_tier_base_active CHECK (min_quality <> 0 OR active)
);

INSERT INTO quality_tier (id, code, min_quality, label_de, label_en, sort_order, active)
VALUES ('6b1f2e0a-3c1d-4f5e-9a10-000000000000', 'NONE', 0, 'Keine', 'None', 0, TRUE),
       ('6b1f2e0a-3c1d-4f5e-9a10-000000000650', 'GOOD', 650, 'Gut (650+)', 'Good (650+)', 650, TRUE);

INSERT INTO quality_tier (id, code, min_quality, label_de, label_en, sort_order, active)
SELECT gen_random_uuid(),
       'Q' || legacy.min_quality,
       legacy.min_quality,
       'Mindestens ' || legacy.min_quality,
       'At least ' || legacy.min_quality,
       legacy.min_quality,
       FALSE
  FROM (SELECT DISTINCT min_quality
          FROM job_order_material
         WHERE min_quality IS NOT NULL
           AND min_quality BETWEEN 1 AND 1000
           AND min_quality <> 650) AS legacy;
