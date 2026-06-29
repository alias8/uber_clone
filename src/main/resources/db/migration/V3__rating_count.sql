ALTER TABLE users   ADD COLUMN rating_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE drivers ADD COLUMN rating_count INTEGER NOT NULL DEFAULT 0;

-- Backfill existing rows from the ratings table
UPDATE users u
SET rating_count = (SELECT COUNT(*) FROM ratings r WHERE r.to_user_id = u.id);

UPDATE drivers d
SET rating_count = (SELECT COUNT(*) FROM ratings r WHERE r.to_user_id = d.user_id);
