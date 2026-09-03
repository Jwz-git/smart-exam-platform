INSERT INTO app_user (username, password_hash, display_name, role, status) VALUES
('admin', '$2y$10$8tORNU/KLJoQ/Yh1Kx0sjugitBRh98az8NnHafXg230yI2p3sglja', '系统管理员', 'ADMIN', 'ACTIVE'),
('teacher', '$2y$10$8tORNU/KLJoQ/Yh1Kx0sjugitBRh98az8NnHafXg230yI2p3sglja', '演示教师', 'TEACHER', 'ACTIVE'),
('student', '$2y$10$8tORNU/KLJoQ/Yh1Kx0sjugitBRh98az8NnHafXg230yI2p3sglja', '演示学生', 'STUDENT', 'ACTIVE'),
('teacher2', '$2y$10$8tORNU/KLJoQ/Yh1Kx0sjugitBRh98az8NnHafXg230yI2p3sglja', '另一教师', 'TEACHER', 'ACTIVE'),
('disabled', '$2y$10$8tORNU/KLJoQ/Yh1Kx0sjugitBRh98az8NnHafXg230yI2p3sglja', '禁用学生', 'STUDENT', 'DISABLED');
-- 排名与权限隔离测试需要多名学生：验收用例 7 的 `1、2、2、4` 竞赛排名至少要四份不同分数的答卷。
INSERT INTO app_user (username, password_hash, display_name, role, status) VALUES
('student2', '$2y$10$8tORNU/KLJoQ/Yh1Kx0sjugitBRh98az8NnHafXg230yI2p3sglja', '学生乙', 'STUDENT', 'ACTIVE'),
('student3', '$2y$10$8tORNU/KLJoQ/Yh1Kx0sjugitBRh98az8NnHafXg230yI2p3sglja', '学生丙', 'STUDENT', 'ACTIVE'),
('student4', '$2y$10$8tORNU/KLJoQ/Yh1Kx0sjugitBRh98az8NnHafXg230yI2p3sglja', '学生丁', 'STUDENT', 'ACTIVE');
