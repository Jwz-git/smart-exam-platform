-- V5：可编辑系统设置 + 错题重练。
--
-- 两张表都刻意不改动既有表结构：设置只保存「被覆盖的那几项」，错题练习记录独立于答卷。
-- 后者尤其重要——重练绝不能改动 submission_answer，否则已公布的成绩会被学生自己的练习改写。

-- 系统设置覆盖值。
--
-- 只保存与默认值不同的项：environment 里的属性仍是默认值，本表是「覆盖层」。
-- 因此「恢复默认」实现为删除该行，而不是写回一个可能过时的默认值；
-- 界面也据此显示每一项到底来自环境变量还是数据库覆盖，不会出现「显示值与生效值不一致」。
--
-- 可覆盖的键由后端 SettingsCatalog 白名单固定，本表不接受未知键：
-- 数据库连接、JWT 密钥、AI 密钥与地址一律只来自环境变量，不进入本表。
CREATE TABLE system_setting (
    setting_key VARCHAR(64) NOT NULL COMMENT '设置键，取值由后端白名单固定',
    setting_value VARCHAR(255) NOT NULL COMMENT '覆盖值，按键的类型解析为整数/小数/布尔',
    updated_by BIGINT UNSIGNED NULL COMMENT '最后修改人，仅管理员可修改',
    updated_at DATETIME(3) NOT NULL COMMENT '最后修改时间，由后端显式写入（不用数据库默认值，避免时区偏移）',
    PRIMARY KEY (setting_key),
    CONSTRAINT fk_system_setting_user FOREIGN KEY (updated_by) REFERENCES app_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统设置覆盖层，只存被改过的项';

-- 错题重练记录。
--
-- 一次练习一行，保留全部历史而不是只留最新一次：这样既能算「练了几次」，
-- 也能回答「练到第几次才对」。「已掌握」的判据是最近一次练习正确（取 MAX(id) 那一行）。
--
-- 指向 paper_question 而不是 question：错题来自某次考试的具体一道题，
-- 题库里的原题之后可能被编辑，练的必须仍是当时那一道（快照）。
CREATE TABLE practice_attempt (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    student_id BIGINT UNSIGNED NOT NULL COMMENT '练习的学生，只能是本人',
    paper_question_id BIGINT UNSIGNED NOT NULL COMMENT '所练的试卷题目（快照），错题来源',
    answer_content JSON NULL COMMENT '本次练习提交的作答，NULL 表示未作答',
    correct TINYINT(1) NOT NULL COMMENT '是否答对，判定复用交卷时的同一套客观题比较逻辑',
    attempted_at DATETIME(3) NOT NULL COMMENT '练习时间，由后端显式写入',
    PRIMARY KEY (id),
    KEY idx_practice_student_question (student_id, paper_question_id, id),
    CONSTRAINT fk_practice_attempt_student FOREIGN KEY (student_id) REFERENCES app_user (id),
    CONSTRAINT fk_practice_attempt_question FOREIGN KEY (paper_question_id) REFERENCES paper_question (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='错题重练记录，独立于答卷，不影响任何成绩';
