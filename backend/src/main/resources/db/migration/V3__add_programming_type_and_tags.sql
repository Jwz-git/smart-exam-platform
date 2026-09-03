-- 新增编程题题型：与简答题一样由教师人工评分，不参与客观题自动判分。
ALTER TABLE question
    MODIFY COLUMN type ENUM('SINGLE_CHOICE', 'MULTIPLE_CHOICE', 'TRUE_FALSE', 'SHORT_ANSWER', 'PROGRAMMING') NOT NULL;

-- 题目关键词标签：以英文逗号分隔的短标签，仅用于列表展示和关键词检索，不参与判分。
ALTER TABLE question
    ADD COLUMN tags VARCHAR(200) NULL AFTER difficulty;
