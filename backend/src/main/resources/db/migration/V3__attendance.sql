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
    CONSTRAINT fk_attendance_workplace FOREIGN KEY (workplace_id) REFERENCES workplace(id),
    CONSTRAINT fk_attendance_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
    CONSTRAINT ck_attendance_type CHECK (event_type IN ('IN', 'OUT')),
    CONSTRAINT uq_attendance_request UNIQUE(user_id, request_id),
    CONSTRAINT uq_attendance_shift_type UNIQUE(user_id, business_date, event_type),
    CONSTRAINT uq_attendance_challenge UNIQUE(user_id, qr_nonce)
);
CREATE INDEX idx_attendance_user_date ON attendance_event(user_id, business_date);

CREATE TABLE attendance_schedule (
 user_id BIGINT NOT NULL,
 business_date DATE NOT NULL,
 workplace_id BIGINT NOT NULL,
 rule_json VARCHAR(4000) NOT NULL,
 holiday BOOLEAN NOT NULL DEFAULT FALSE,
 created_by BIGINT NOT NULL,
 PRIMARY KEY(user_id,business_date),
 CONSTRAINT fk_schedule_user FOREIGN KEY(user_id) REFERENCES sys_user(id),
 CONSTRAINT fk_schedule_workplace FOREIGN KEY(workplace_id) REFERENCES workplace(id)
);
CREATE TABLE attendance_adjustment (
 approval_id BIGINT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 company_id BIGINT NOT NULL,
 draft_json VARCHAR(4000) NOT NULL,
 approver_id BIGINT NOT NULL,
 approved_at TIMESTAMP NOT NULL,
 CONSTRAINT fk_adjustment_user FOREIGN KEY(user_id) REFERENCES sys_user(id)
);
CREATE INDEX idx_adjustment_user ON attendance_adjustment(user_id);
