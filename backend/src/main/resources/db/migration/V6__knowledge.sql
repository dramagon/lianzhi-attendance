CREATE TABLE knowledge_lock (id INT PRIMARY KEY);
INSERT INTO knowledge_lock(id) VALUES (1);

CREATE TABLE knowledge_document (
    id CHAR(36) PRIMARY KEY,
    version BIGINT NOT NULL,
    company_id BIGINT NOT NULL,
    owner_user_id BIGINT NOT NULL,
    visibility VARCHAR(16) NOT NULL,
    name VARCHAR(200) NOT NULL,
    sha256 CHAR(64) NOT NULL,
    byte_size BIGINT NOT NULL,
    original_bytes LONGBLOB,
    processing VARCHAR(24) NOT NULL,
    indexing VARCHAR(24) NOT NULL,
    message VARCHAR(300) NOT NULL,
    chunk_count INT NOT NULL DEFAULT 0,
    text_size BIGINT NOT NULL DEFAULT 0,
    vector_values BIGINT NOT NULL DEFAULT 0,
    index_model CHAR(64),
    lease_token CHAR(36),
    lease_until BIGINT,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    CONSTRAINT fk_knowledge_company FOREIGN KEY (company_id) REFERENCES org_company(id),
    CONSTRAINT fk_knowledge_owner FOREIGN KEY (owner_user_id) REFERENCES sys_user(id)
);
CREATE INDEX idx_knowledge_acl ON knowledge_document(company_id,visibility,owner_user_id);
CREATE INDEX idx_knowledge_queue ON knowledge_document(processing,created_at);

CREATE TABLE knowledge_chunk (
    document_id CHAR(36) NOT NULL,
    version BIGINT NOT NULL,
    sequence_no INT NOT NULL,
    chunk_json TEXT NOT NULL,
    vector_json LONGTEXT,
    PRIMARY KEY (document_id,sequence_no),
    CONSTRAINT fk_chunk_document FOREIGN KEY (document_id) REFERENCES knowledge_document(id)
);
CREATE TABLE knowledge_revision (
    document_id CHAR(36) NOT NULL,
    version BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    action VARCHAR(20) NOT NULL,
    name VARCHAR(200) NOT NULL,
    sha256 CHAR(64) NOT NULL,
    occurred_at BIGINT NOT NULL,
    PRIMARY KEY (document_id,version),
    CONSTRAINT fk_revision_document FOREIGN KEY (document_id) REFERENCES knowledge_document(id)
);
