BEGIN READ ONLY;

SELECT count(*) AS areas_with_multiple_leaders
FROM (
    SELECT org_unit_id
    FROM org_unit_membership
    WHERE role = 'BEREICHSLEITER'
    GROUP BY org_unit_id
    HAVING count(*) > 1
) AS duplicates;

COMMIT;
