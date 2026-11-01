-- CANDIDATE ONLY: activate after reviewed A V4, authorized SDK adapter and encryption key.
CREATE TABLE face_enrollment (
 user_id BIGINT PRIMARY KEY,
 revision BIGINT NOT NULL,
 provider VARCHAR(100) NOT NULL,
 feature_version VARCHAR(100) NOT NULL,
 feature_cipher TEXT NOT NULL,
 updated_at TIMESTAMP NOT NULL,
 CONSTRAINT fk_face_employee FOREIGN KEY(user_id) REFERENCES sys_user(id)
);
CREATE TABLE face_challenge (
 id VARCHAR(36) PRIMARY KEY,
 user_id BIGINT NOT NULL,
 workplace_id BIGINT NOT NULL,
 request_id VARCHAR(36) NOT NULL,
 event_type VARCHAR(3) NOT NULL,
 qr_nonce VARCHAR(36) NOT NULL,
 enrollment_revision BIGINT NOT NULL,
 expires_at TIMESTAMP NOT NULL,
 attempts INT NOT NULL DEFAULT 0,
 verified_at TIMESTAMP NULL,
 consumed_at TIMESTAMP NULL,
 CONSTRAINT fk_face_challenge_user FOREIGN KEY(user_id) REFERENCES sys_user(id),
 CONSTRAINT fk_face_challenge_place FOREIGN KEY(workplace_id) REFERENCES workplace(id),
 CONSTRAINT uq_face_request UNIQUE(user_id,request_id),
 CONSTRAINT ck_face_event CHECK(event_type IN ('IN','OUT'))
);
CREATE INDEX idx_face_expiry ON face_challenge(expires_at);
CREATE TABLE face_audit (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 action VARCHAR(20) NOT NULL,
 enrollment_revision BIGINT NOT NULL,
 occurred_at TIMESTAMP NOT NULL,
 CONSTRAINT fk_face_audit_user FOREIGN KEY(user_id) REFERENCES sys_user(id)
);
