-- 补充演示学生账号，密码与 V2 相同：ExamDemo123!
-- 用途是支撑 plan.md 第 7.1 节的验收前置数据（学生甲、学生乙）以及第 7.2 节用例 7 的
-- `1、2、2、4` 竞赛排名演示——两名学生无法演示同分并列，因此一次补到四名。
-- 与 V2 一样，这些账号只用于课程演示，任何非演示部署都必须先替换或禁用。
INSERT INTO app_user (username, password_hash, display_name, role, status)
VALUES
    ('student2', '$2y$10$8tORNU/KLJoQ/Yh1Kx0sjugitBRh98az8NnHafXg230yI2p3sglja', '学生乙', 'STUDENT', 'ACTIVE'),
    ('student3', '$2y$10$8tORNU/KLJoQ/Yh1Kx0sjugitBRh98az8NnHafXg230yI2p3sglja', '学生丙', 'STUDENT', 'ACTIVE'),
    ('student4', '$2y$10$8tORNU/KLJoQ/Yh1Kx0sjugitBRh98az8NnHafXg230yI2p3sglja', '学生丁', 'STUDENT', 'ACTIVE');
