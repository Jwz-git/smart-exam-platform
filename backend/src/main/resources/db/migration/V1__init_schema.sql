CREATE TABLE app_user (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(64) NOT NULL,
    role ENUM('ADMIN', 'TEACHER', 'STUDENT') NOT NULL,
    status ENUM('ACTIVE', 'DISABLED') NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_point (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500) NULL,
    created_by BIGINT UNSIGNED NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_point_name (name),
    CONSTRAINT fk_knowledge_point_creator FOREIGN KEY (created_by) REFERENCES app_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE question (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    type ENUM('SINGLE_CHOICE', 'MULTIPLE_CHOICE', 'TRUE_FALSE', 'SHORT_ANSWER') NOT NULL,
    stem TEXT NOT NULL,
    difficulty ENUM('EASY', 'MEDIUM', 'HARD') NOT NULL,
    standard_answer JSON NOT NULL,
    explanation TEXT NULL,
    suggested_score DECIMAL(6,1) NOT NULL,
    knowledge_point_id BIGINT UNSIGNED NOT NULL,
    created_by BIGINT UNSIGNED NOT NULL,
    status ENUM('ACTIVE', 'DISABLED') NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_question_filter (type, difficulty, knowledge_point_id, status),
    CONSTRAINT ck_question_suggested_score CHECK (suggested_score > 0),
    CONSTRAINT fk_question_knowledge_point FOREIGN KEY (knowledge_point_id) REFERENCES knowledge_point (id),
    CONSTRAINT fk_question_creator FOREIGN KEY (created_by) REFERENCES app_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE question_option (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    question_id BIGINT UNSIGNED NOT NULL,
    option_key VARCHAR(8) NOT NULL,
    content TEXT NOT NULL,
    display_order SMALLINT UNSIGNED NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_question_option_key (question_id, option_key),
    UNIQUE KEY uk_question_option_order (question_id, display_order),
    CONSTRAINT fk_question_option_question FOREIGN KEY (question_id) REFERENCES question (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE paper (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name VARCHAR(150) NOT NULL,
    duration_minutes SMALLINT UNSIGNED NOT NULL,
    total_score DECIMAL(6,1) NOT NULL DEFAULT 0.0,
    status ENUM('DRAFT', 'PUBLISHED') NOT NULL DEFAULT 'DRAFT',
    created_by BIGINT UNSIGNED NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT ck_paper_duration CHECK (duration_minutes > 0),
    CONSTRAINT ck_paper_total_score CHECK (total_score >= 0),
    CONSTRAINT fk_paper_creator FOREIGN KEY (created_by) REFERENCES app_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE paper_question (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    paper_id BIGINT UNSIGNED NOT NULL,
    question_id BIGINT UNSIGNED NOT NULL,
    display_order SMALLINT UNSIGNED NOT NULL,
    score DECIMAL(6,1) NOT NULL,
    type_snapshot VARCHAR(32) NOT NULL,
    stem_snapshot TEXT NOT NULL,
    options_snapshot JSON NULL,
    answer_snapshot JSON NOT NULL,
    explanation_snapshot TEXT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_paper_question_order (paper_id, display_order),
    UNIQUE KEY uk_paper_question_question (paper_id, question_id),
    CONSTRAINT ck_paper_question_score CHECK (score > 0),
    CONSTRAINT fk_paper_question_paper FOREIGN KEY (paper_id) REFERENCES paper (id),
    CONSTRAINT fk_paper_question_question FOREIGN KEY (question_id) REFERENCES question (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE exam (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name VARCHAR(150) NOT NULL,
    paper_id BIGINT UNSIGNED NOT NULL,
    created_by BIGINT UNSIGNED NOT NULL,
    start_at TIMESTAMP(6) NOT NULL,
    end_at TIMESTAMP(6) NOT NULL,
    status ENUM('DRAFT', 'PUBLISHED', 'IN_PROGRESS', 'ENDED', 'GRADED', 'RESULTS_PUBLISHED') NOT NULL DEFAULT 'DRAFT',
    results_published_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_exam_window (status, start_at, end_at),
    CONSTRAINT ck_exam_time_range CHECK (end_at > start_at),
    CONSTRAINT fk_exam_paper FOREIGN KEY (paper_id) REFERENCES paper (id),
    CONSTRAINT fk_exam_creator FOREIGN KEY (created_by) REFERENCES app_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE submission (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    exam_id BIGINT UNSIGNED NOT NULL,
    student_id BIGINT UNSIGNED NOT NULL,
    status ENUM('IN_PROGRESS', 'SUBMITTED', 'GRADED') NOT NULL DEFAULT 'IN_PROGRESS',
    started_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    submitted_at TIMESTAMP(6) NULL,
    objective_score DECIMAL(6,1) NOT NULL DEFAULT 0.0,
    subjective_score DECIMAL(6,1) NOT NULL DEFAULT 0.0,
    total_score DECIMAL(6,1) NOT NULL DEFAULT 0.0,
    version INT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_submission_exam_student (exam_id, student_id),
    KEY idx_submission_student (student_id, status),
    CONSTRAINT fk_submission_exam FOREIGN KEY (exam_id) REFERENCES exam (id),
    CONSTRAINT fk_submission_student FOREIGN KEY (student_id) REFERENCES app_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE submission_answer (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    submission_id BIGINT UNSIGNED NOT NULL,
    paper_question_id BIGINT UNSIGNED NOT NULL,
    answer_content JSON NULL,
    score DECIMAL(6,1) NOT NULL DEFAULT 0.0,
    grading_comment VARCHAR(1000) NULL,
    graded_by BIGINT UNSIGNED NULL,
    graded_at TIMESTAMP(6) NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_submission_answer_question (submission_id, paper_question_id),
    CONSTRAINT ck_submission_answer_score CHECK (score >= 0),
    CONSTRAINT fk_submission_answer_submission FOREIGN KEY (submission_id) REFERENCES submission (id),
    CONSTRAINT fk_submission_answer_paper_question FOREIGN KEY (paper_question_id) REFERENCES paper_question (id),
    CONSTRAINT fk_submission_answer_grader FOREIGN KEY (graded_by) REFERENCES app_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
