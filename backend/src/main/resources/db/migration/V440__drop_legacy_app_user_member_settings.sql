-- Drops the two member settings that V283 copied into member_payout_setting and
-- member_blueprint_sharing; no code reads or writes the app_user columns.
ALTER TABLE app_user
    DROP COLUMN IF EXISTS default_payout_preference,
    DROP COLUMN IF EXISTS share_blueprints_globally;
