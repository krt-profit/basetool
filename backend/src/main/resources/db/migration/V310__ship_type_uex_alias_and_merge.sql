CREATE TABLE ship_type_uex_alias (
    uex_vehicle_id INTEGER      PRIMARY KEY,
    class_name     VARCHAR(255) NOT NULL
);

CREATE UNIQUE INDEX uk_ship_type_uex_alias_class_name
    ON ship_type_uex_alias (lower(class_name));

INSERT INTO ship_type_uex_alias (uex_vehicle_id, class_name) VALUES
    (17,  'CRUS_Starfighter_Inferno'),
    (18,  'CRUS_Starfighter_Ion'),
    (76,  'ANVL_Hornet_F7CM_Heartseeker'),
    (98,  'AEGS_Hammerhead_Showdown'),
    (137, 'TMBL_Nova'),
    (160, 'AEGS_Reclaimer_Showdown'),
    (173, 'XNAA_SanTokYai'),
    (258, 'ARGO_ATLS_IKTI'),
    (259, 'ARGO_ATLS_IKTI_ARGOS'),
    (260, 'ARGO_ATLS_GEO_IKTI'),
    (265, 'VNCL_Stinger'),
    (274, 'KRIG_S65_Stingray_BALLISTIC');

CREATE TEMP TABLE _ship_type_merge ON COMMIT DROP AS
SELECT u.id             AS loser_id,
       w.id             AS survivor_id,
       u.uex_vehicle_id AS uex_vehicle_id
FROM ship_type_uex_alias a
JOIN ship_type u ON u.uex_vehicle_id = a.uex_vehicle_id
JOIN ship_type w ON lower(w.class_name) = lower(a.class_name)
WHERE u.id <> w.id
  AND u.source_systems = 'UEX_ONLY'
  AND u.class_name IS NULL
  AND w.uex_vehicle_id IS NULL
  AND (SELECT count(*) FROM ship_type x WHERE lower(x.class_name) = lower(a.class_name)) = 1;

UPDATE ship_type w
   SET manufacturer_id    = COALESCE(w.manufacturer_id, u.manufacturer_id),
       uex_slug           = u.uex_slug,
       name_full          = u.name_full,
       scu                = u.scu,
       crew_min           = u.crew_min,
       crew_max           = u.crew_max,
       mass               = u.mass,
       width              = u.width,
       height             = u.height,
       length_m           = u.length_m,
       pad_type           = u.pad_type,
       fuel_quantum       = u.fuel_quantum,
       fuel_hydrogen      = u.fuel_hydrogen,
       container_sizes    = u.container_sizes,
       url_store          = u.url_store,
       url_brochure       = u.url_brochure,
       url_hotsite        = u.url_hotsite,
       url_photo          = u.url_photo,
       url_video          = u.url_video,
       is_addon           = u.is_addon,
       is_boarding        = u.is_boarding,
       is_bomber          = u.is_bomber,
       is_cargo           = u.is_cargo,
       is_carrier         = u.is_carrier,
       is_civilian        = u.is_civilian,
       is_concept         = u.is_concept,
       is_construction    = u.is_construction,
       is_datarunner      = u.is_datarunner,
       is_docking         = u.is_docking,
       is_emp             = u.is_emp,
       is_exploration     = u.is_exploration,
       is_ground_vehicle  = u.is_ground_vehicle,
       is_hangar          = u.is_hangar,
       is_industrial      = u.is_industrial,
       is_interdiction    = u.is_interdiction,
       is_loading_dock    = u.is_loading_dock,
       is_medical         = u.is_medical,
       is_military        = u.is_military,
       is_mining          = u.is_mining,
       is_passenger       = u.is_passenger,
       is_qed             = u.is_qed,
       is_quantum_capable = u.is_quantum_capable,
       is_racing          = u.is_racing,
       is_refinery        = u.is_refinery,
       is_refuel          = u.is_refuel,
       is_repair          = u.is_repair,
       is_research        = u.is_research,
       is_salvage         = u.is_salvage,
       is_scanning        = u.is_scanning,
       is_science         = u.is_science,
       is_showdown_winner = u.is_showdown_winner,
       is_spaceship       = u.is_spaceship,
       is_starter         = u.is_starter,
       is_stealth         = u.is_stealth,
       is_tractor_beam    = u.is_tractor_beam,
       uex_synced_at      = u.uex_synced_at,
       uex_deleted_at     = u.uex_deleted_at,
       source_systems     = CASE WHEN w.source_systems = 'WIKI_ONLY' THEN 'BOTH'
                                 ELSE w.source_systems END,
       version            = COALESCE(w.version, 0) + 1
  FROM _ship_type_merge m
  JOIN ship_type u ON u.id = m.loser_id
 WHERE w.id = m.survivor_id;

UPDATE ship s
   SET ship_type_id = m.survivor_id,
       version      = COALESCE(s.version, 0) + 1
  FROM _ship_type_merge m
 WHERE s.ship_type_id = m.loser_id;

UPDATE mission_unit mu
   SET ship_type_id = m.survivor_id,
       version      = COALESCE(mu.version, 0) + 1
  FROM _ship_type_merge m
 WHERE mu.ship_type_id = m.loser_id;

UPDATE game_item gi
   SET linked_ship_type_id = m.survivor_id,
       version             = COALESCE(gi.version, 0) + 1
  FROM _ship_type_merge m
 WHERE gi.linked_ship_type_id = m.loser_id;

DELETE FROM ship_type u
 USING _ship_type_merge m
 WHERE u.id = m.loser_id;

UPDATE ship_type w
   SET uex_vehicle_id = m.uex_vehicle_id
  FROM _ship_type_merge m
 WHERE w.id = m.survivor_id;
