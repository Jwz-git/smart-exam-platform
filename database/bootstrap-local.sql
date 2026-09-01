-- Local development only. Run as a MySQL administrator:
-- mysql -uroot -p < database/bootstrap-local.sql

CREATE DATABASE IF NOT EXISTS smart_exam
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

CREATE USER IF NOT EXISTS 'smart_exam'@'localhost' IDENTIFIED BY 'smart_exam_dev';
ALTER USER 'smart_exam'@'localhost' IDENTIFIED BY 'smart_exam_dev';
GRANT ALL PRIVILEGES ON smart_exam.* TO 'smart_exam'@'localhost';
FLUSH PRIVILEGES;
