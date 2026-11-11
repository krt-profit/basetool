ALTER TABLE job_order
    ADD COLUMN lifecycle_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN responsibility_version BIGINT NOT NULL DEFAULT 0;

UPDATE job_order_participation AS participation
SET active = FALSE,
    version = participation.version + 1
FROM job_order AS orders
WHERE participation.job_order_id = orders.id
  AND participation.claiming_org_unit_id = orders.responsible_org_unit_id
  AND participation.active;
