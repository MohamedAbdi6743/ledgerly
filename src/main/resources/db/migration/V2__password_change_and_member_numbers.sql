-- New accounts created by staff get a temporary password that must be changed at first login.
ALTER TABLE app_user ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;

-- Member numbers (M000001, M000002, ...) come from a sequence, so two tellers creating
-- members at the same moment can never get the same number.
CREATE SEQUENCE member_number_seq START WITH 1;
