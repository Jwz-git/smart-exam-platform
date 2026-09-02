-- Development/demo accounts. Password for all three accounts: ExamDemo123!
-- Replace or disable these accounts before any non-demonstration deployment.
INSERT INTO app_user (username, password_hash, display_name, role, status)
VALUES
    ('admin', '$2y$10$8tORNU/KLJoQ/Yh1Kx0sjugitBRh98az8NnHafXg230yI2p3sglja', '系统管理员', 'ADMIN', 'ACTIVE'),
    ('teacher', '$2y$10$8tORNU/KLJoQ/Yh1Kx0sjugitBRh98az8NnHafXg230yI2p3sglja', '演示教师', 'TEACHER', 'ACTIVE'),
    ('student', '$2y$10$8tORNU/KLJoQ/Yh1Kx0sjugitBRh98az8NnHafXg230yI2p3sglja', '演示学生', 'STUDENT', 'ACTIVE');
