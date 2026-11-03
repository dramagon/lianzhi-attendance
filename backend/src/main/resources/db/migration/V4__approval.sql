CREATE TABLE approval_request (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    applicant_id BIGINT NOT NULL,
    company_id BIGINT NOT NULL,
    department_id BIGINT,
    request_key VARCHAR(36) NOT NULL,
    request_type VARCHAR(20) NOT NULL,
    state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    start_at DATETIME NOT NULL,
    end_at DATETIME NULL,
    punch_type VARCHAR(3),
    workplace_id BIGINT,
    reason VARCHAR(500) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_approval_applicant FOREIGN KEY (applicant_id) REFERENCES sys_user(id),
    CONSTRAINT fk_approval_company FOREIGN KEY (company_id) REFERENCES org_company(id),
    CONSTRAINT fk_approval_department FOREIGN KEY (department_id) REFERENCES sys_department(id),
    CONSTRAINT fk_approval_workplace FOREIGN KEY (workplace_id) REFERENCES workplace(id),
    CONSTRAINT uq_approval_request_key UNIQUE (applicant_id, request_key),
    CONSTRAINT ck_approval_type CHECK (request_type IN ('LEAVE','MISSING_PUNCH','OVERTIME')),
    CONSTRAINT ck_approval_state CHECK (state IN ('PENDING','APPROVED','REJECTED','WITHDRAWN')),
    CONSTRAINT ck_approval_times CHECK ((request_type='MISSING_PUNCH' AND end_at IS NULL AND punch_type IS NOT NULL AND punch_type IN ('IN','OUT') AND workplace_id IS NOT NULL) OR (request_type IN ('LEAVE','OVERTIME') AND end_at IS NOT NULL AND end_at>start_at AND punch_type IS NULL AND workplace_id IS NULL))
);
CREATE INDEX idx_approval_applicant ON approval_request(applicant_id, created_at);
CREATE INDEX idx_approval_company_state ON approval_request(company_id, state, created_at);
CREATE TABLE approval_transition (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    approval_id BIGINT NOT NULL,
    from_state VARCHAR(20) NOT NULL,
    to_state VARCHAR(20) NOT NULL,
    actor_id BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    occurred_at DATETIME NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT fk_transition_approval FOREIGN KEY (approval_id) REFERENCES approval_request(id),
    CONSTRAINT fk_transition_actor FOREIGN KEY (actor_id) REFERENCES sys_user(id),
    CONSTRAINT uq_approval_transition_version UNIQUE (approval_id, version)
);
