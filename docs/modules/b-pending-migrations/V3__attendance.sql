-- Staged, NOT in Flyway's active locations. Move only after A's V2 and real directory adapter are reviewed.
-- workplace_id refers to A's workplace identity; confirm foreign key target with A before activation.
CREATE TABLE attendance_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    workplace_id BIGINT NOT NULL,
    business_date DATE NOT NULL,
    event_type VARCHAR(3) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    qr_nonce VARCHAR(36) NOT NULL,
    distance_meters DOUBLE NOT NULL,
    CONSTRAINT fk_attendance_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
    CONSTRAINT ck_attendance_type CHECK (event_type IN ('IN', 'OUT')),
    CONSTRAINT uq_attendance_request UNIQUE(user_id, request_id),
    CONSTRAINT uq_attendance_shift_type UNIQUE(user_id, business_date, event_type),
    CONSTRAINT uq_attendance_challenge UNIQUE(user_id, qr_nonce)
);
CREATE INDEX idx_attendance_user_date ON attendance_event(user_id, business_date);
