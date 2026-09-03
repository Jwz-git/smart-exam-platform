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

CREATE TABLE paper (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(150) NOT NULL,
    duration_minutes SMALLINT NOT NULL,
    total_score DECIMAL(6,1) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_by BIGINT NOT NULL
);

CREATE TABLE paper_question (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    paper_id BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    display_order SMALLINT NOT NULL,
    score DECIMAL(6,1) NOT NULL,
    type_snapshot VARCHAR(32) NOT NULL,
    stem_snapshot CLOB NOT NULL,
    options_snapshot CLOB,
    answer_snapshot CLOB NOT NULL,
    explanation_snapshot CLOB,
    UNIQUE(paper_id, display_order),
    UNIQUE(paper_id, question_id)
);

CREATE TABLE exam (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(150) NOT NULL,
    paper_id BIGINT NOT NULL,
    created_by BIGINT NOT NULL,
    start_at TIMESTAMP WITH TIME ZONE NOT NULL,
    end_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(32) NOT NULL,
    results_published_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE submission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    exam_id BIGINT NOT NULL,
    student_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    submitted_at TIMESTAMP WITH TIME ZONE,
    objective_score DECIMAL(6,1) DEFAULT 0 NOT NULL,
    subjective_score DECIMAL(6,1) DEFAULT 0 NOT NULL,
    total_score DECIMAL(6,1) DEFAULT 0 NOT NULL,
    version INT DEFAULT 0 NOT NULL,
    UNIQUE(exam_id, student_id)
);

CREATE TABLE submission_answer (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    submission_id BIGINT NOT NULL,
    paper_question_id BIGINT NOT NULL,
    answer_content CLOB,
    score DECIMAL(6,1) DEFAULT 0 NOT NULL,
    grading_comment VARCHAR(1000),
    graded_by BIGINT,
    graded_at TIMESTAMP WITH TIME ZONE,
    UNIQUE(submission_id, paper_question_id)
);
