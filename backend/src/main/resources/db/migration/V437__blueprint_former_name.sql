CREATE TABLE blueprint_former_name (
    blueprint_id UUID         NOT NULL REFERENCES blueprint (id) ON DELETE CASCADE,
    name         VARCHAR(255) NOT NULL,
    CONSTRAINT pk_blueprint_former_name PRIMARY KEY (blueprint_id, name)
);

-- REQ-P4K-079: the output names earlier APPLYs replaced, read from their change reports, where the
-- old name belonged to one product (its produced-item GUID, else its current name).
WITH renamed AS (
    SELECT DISTINCT b.id                                                   AS blueprint_id,
                    btrim(c.old_value)                                     AS name,
                    COALESCE(b.produced_item_guid::text, lower(b.output_name)) AS product
    FROM p4k_import_change c
             JOIN blueprint b ON b.p4k_uuid = c.guid
    WHERE c.kind = 'BLUEPRINT'
      AND c.change_type = 'CHANGED'
      AND c.field_name = 'output_name'
      AND btrim(c.old_value) <> ''
      AND b.output_name IS NOT NULL
      AND lower(btrim(c.old_value)) <> lower(btrim(b.output_name))
),
     one_product AS (
         SELECT lower(name) AS folded
         FROM renamed
         GROUP BY lower(name)
         HAVING COUNT(DISTINCT product) = 1
     )
INSERT INTO blueprint_former_name (blueprint_id, name)
SELECT DISTINCT ON (r.blueprint_id, lower(r.name)) r.blueprint_id, r.name
FROM renamed r
         JOIN one_product o ON o.folded = lower(r.name)
ORDER BY r.blueprint_id, lower(r.name), r.name;
