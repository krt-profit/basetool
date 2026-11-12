BEGIN TRANSACTION READ ONLY;

SELECT m.id AS mission_id,
       m.owning_org_unit_id AS mission_org_unit_id,
       m.status AS mission_status,
       m.created_at AS mission_created_at,
       m.updated_at AS mission_updated_at,
       o.id AS operation_id,
       o.owning_org_unit_id AS operation_org_unit_id,
       o.status AS operation_status,
       o.created_at AS operation_created_at,
       o.updated_at AS operation_updated_at
  FROM mission m
  JOIN operation o ON o.id = m.operation_id
 WHERE m.owning_org_unit_id IS DISTINCT FROM o.owning_org_unit_id
 ORDER BY o.id, m.id;

COMMIT;
