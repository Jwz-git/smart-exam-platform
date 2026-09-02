DROP TABLE IF EXISTS app_user;
CREATE TABLE app_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(64) NOT NULL,
    role VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL
);

CREATE TABLE knowledge_point (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(500),
    created_by BIGINT NOT NULL
);

CREATE TABLE question (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    type VARCHAR(32) NOT NULL,
    stem CLOB NOT NULL,
    difficulty VARCHAR(16) NOT NULL,
    standard_answer CLOB NOT NULL,
    explanation CLOB,
    suggested_score DECIMAL(6,1) NOT NULL,
    knowledge_point_id BIGINT NOT NULL,
    created_by BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL
);

CREATE TABLE question_option (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    question_id BIGINT NOT NULL,
    option_key VARCHAR(8) NOT NULL,
    content CLOB NOT NULL,
    display_order SMALLINT NOT NULL,
    UNIQUE(question_id, option_key),
    UNIQUE(question_id, display_order)
);

CREATE TABLE paper_question (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    question_id BIGINT NOT NULL
);
