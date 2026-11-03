-- Integration fix: preserve server event order for punches within the same second.
-- Existing whole-second values remain unchanged. V3 is already published.
ALTER TABLE attendance_event MODIFY COLUMN occurred_at TIMESTAMP(6) NOT NULL;
