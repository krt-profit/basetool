ALTER TABLE ship_type_redirect ADD COLUMN uex_vehicle_id INTEGER;

CREATE INDEX idx_ship_type_redirect_uex_vehicle ON ship_type_redirect (uex_vehicle_id);
