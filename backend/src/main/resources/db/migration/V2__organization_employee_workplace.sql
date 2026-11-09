CREATE TABLE org_company (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE
);
ALTER TABLE sys_department ADD COLUMN company_id BIGINT;
ALTER TABLE sys_department ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE sys_department ADD CONSTRAINT fk_department_company FOREIGN KEY (company_id) REFERENCES org_company(id);
ALTER TABLE sys_user ADD COLUMN company_id BIGINT;
ALTER TABLE sys_user ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE sys_user ADD CONSTRAINT fk_user_company FOREIGN KEY (company_id) REFERENCES org_company(id);
CREATE INDEX idx_user_company ON sys_user(company_id);
CREATE TABLE workplace (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    latitude DECIMAL(10,7) NOT NULL,
    longitude DECIMAL(10,7) NOT NULL,
    coordinate_system VARCHAR(10) NOT NULL,
    radius_meters INT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT fk_workplace_company FOREIGN KEY (company_id) REFERENCES org_company(id),
    CONSTRAINT uq_workplace_name UNIQUE (company_id, name),
    CONSTRAINT ck_workplace_latitude CHECK (latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_workplace_longitude CHECK (longitude BETWEEN -180 AND 180),
    CONSTRAINT ck_workplace_radius CHECK (radius_meters BETWEEN 10 AND 10000),
    CONSTRAINT ck_workplace_coordinates CHECK (coordinate_system IN ('WGS84','GCJ02','BD09'))
);
CREATE TABLE employee_workplace (
    user_id BIGINT NOT NULL,
    workplace_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, workplace_id),
    CONSTRAINT fk_binding_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
    CONSTRAINT fk_binding_workplace FOREIGN KEY (workplace_id) REFERENCES workplace(id)
);
