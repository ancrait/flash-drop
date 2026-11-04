ALTER TABLE reservations ALTER COLUMN request_hash DROP NOT NULL;

-- A cancellation received before reservation has no original request to hash.
ALTER TABLE reservations ADD CONSTRAINT chk_reservations_request_hash
    CHECK (request_hash IS NOT NULL OR status = 'RELEASED');
