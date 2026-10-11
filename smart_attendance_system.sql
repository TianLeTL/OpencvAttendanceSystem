  -- ============================================
  -- Smart Attendance System - Schema Only (No Data)
  -- Cleaned for MySQL 8.x
  -- ============================================

  SET SQL_MODE = "NO_AUTO_VALUE_ON_ZERO";
  SET time_zone = "+00:00";

  /*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
  /*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
  /*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
  /*!40101 SET NAMES utf8mb4 */;

  -- ===== Database =====
  CREATE DATABASE IF NOT EXISTS smart_attendance_system
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;
  USE smart_attendance_system;

  -- ============================================
  -- Drop views first (to avoid dependency issues on re-run)
  -- ============================================
  DROP VIEW IF EXISTS `vw_student_attendance_history`;
  DROP VIEW IF EXISTS `vw_session_attendance_summary`;
  DROP VIEW IF EXISTS `vw_active_students`;

  -- ============================================
  -- Stored Procedures (drop-and-create safely)
  -- ============================================
  DELIMITER $$

  DROP PROCEDURE IF EXISTS sp_close_session $$
  CREATE PROCEDURE sp_close_session (IN p_session_id INT, IN p_closed_by_user_id INT)
  BEGIN
      DECLARE v_session_exists INT DEFAULT 0;
      DECLARE v_session_status VARCHAR(20);

      -- Check session exists
      SELECT COUNT(*) INTO v_session_exists
      FROM sessions
      WHERE session_id = p_session_id;

      IF v_session_exists = 0 THEN
          SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Session not found';
      END IF;

      -- Fetch status
      SELECT status INTO v_session_status
      FROM sessions
      WHERE session_id = p_session_id;

      IF v_session_status <> 'OPEN' THEN
          SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Session is not open - cannot close';
      END IF;

      -- Mark unattended roster students as ABSENT (AUTO)
      INSERT INTO attendance_records (session_id, student_id, status, marking_method, marked_by)
      SELECT sr.session_id, sr.student_id, 'ABSENT', 'AUTO', p_closed_by_user_id
      FROM session_roster sr
      LEFT JOIN attendance_records ar
        ON sr.session_id = ar.session_id AND sr.student_id = ar.student_id
      WHERE sr.session_id = p_session_id
        AND ar.attendance_id IS NULL;

      -- Close the session
      UPDATE sessions
      SET status = 'CLOSED',
          closed_at = CURRENT_TIMESTAMP
      WHERE session_id = p_session_id;
  END $$

  DROP PROCEDURE IF EXISTS sp_populate_session_roster $$
  CREATE PROCEDURE sp_populate_session_roster (IN p_session_id INT, IN p_added_by_user_id INT)
  BEGIN
      DECLARE v_section_id INT;
      DECLARE v_session_exists INT DEFAULT 0;

      -- Get section_id for the session
      SELECT COUNT(*), COALESCE(MAX(section_id), 0)
      INTO v_session_exists, v_section_id
      FROM sessions
      WHERE session_id = p_session_id;

      IF v_session_exists = 0 THEN
          SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Session not found';
      END IF;

      -- Insert currently-enrolled students into the roster (ignore dupes)
      INSERT IGNORE INTO session_roster (session_id, student_id, added_by)
      SELECT p_session_id, se.student_id, p_added_by_user_id
      FROM section_enrolments se
      WHERE se.section_id = v_section_id
        AND se.status     = 'ENROLLED';
  END $$
  DELIMITER ;

  -- ============================================
  -- Tables
  -- (Order matters due to FK dependencies)
  -- ============================================

  -- Students
  CREATE TABLE IF NOT EXISTS `students` (
    `student_id` CHAR(8) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '8-char unique student identifier',
    `full_name`  VARCHAR(100) COLLATE utf8mb4_unicode_ci NOT NULL,
    `email`      VARCHAR(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `phone_number` VARCHAR(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `enrolment_date` DATE DEFAULT (curdate()),
    `created_at` TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`student_id`),
    KEY `idx_student_email` (`email`),
    KEY `idx_student_phone` (`phone_number`)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

  -- Users (create before sessions because of FK)
  CREATE TABLE IF NOT EXISTS `users` (
    `user_id` INT NOT NULL AUTO_INCREMENT,
    `username`      VARCHAR(50)  COLLATE utf8mb4_unicode_ci NOT NULL,
    `password_hash` VARCHAR(255) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'Store hashed passwords (bcrypt recommended)',
    `email`         VARCHAR(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `full_name`     VARCHAR(100) COLLATE utf8mb4_unicode_ci NOT NULL,
    `role`          ENUM('ADMIN','PROFESSOR','TEACHING_ASSISTANT') COLLATE utf8mb4_unicode_ci DEFAULT 'ADMIN' COMMENT 'Baseline: ADMIN only; extensible later',
    `created_at`    TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    `last_login`    TIMESTAMP NULL DEFAULT NULL,
    PRIMARY KEY (`user_id`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_user_email` (`email`),
    KEY `idx_username` (`username`),
    KEY `idx_user_email` (`email`)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Administrator authentication and user management';

  -- Courses
  CREATE TABLE IF NOT EXISTS `courses` (
    `course_id`   INT NOT NULL AUTO_INCREMENT,
    `course_code` VARCHAR(20)  COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'e.g., CS102, IS111',
    `course_name` VARCHAR(200) COLLATE utf8mb4_unicode_ci NOT NULL,
    `description` TEXT COLLATE utf8mb4_unicode_ci,
    `created_at`  TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`course_id`),
    UNIQUE KEY `uk_course_code` (`course_code`),
    KEY `idx_course_code` (`course_code`)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Course and module information';

  -- Sections
  CREATE TABLE IF NOT EXISTS `sections` (
    `section_id`    INT NOT NULL AUTO_INCREMENT,
    `course_id`     INT NOT NULL,
    `section_code`  VARCHAR(10)  COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'e.g., G1, G2, A, B',
    `instructor_name` VARCHAR(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
    `professor_id`  INT DEFAULT NULL COMMENT 'Assigned professor responsible for the section',
    `teaching_assistant1_id` INT DEFAULT NULL COMMENT 'Primary teaching assistant for the section',
    `teaching_assistant2_id` INT DEFAULT NULL COMMENT 'Secondary teaching assistant for the section',
    `max_capacity`  INT DEFAULT '50',
    `created_at`    TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`section_id`),
    UNIQUE KEY `uk_course_section` (`course_id`,`section_code`),
    KEY `idx_section_code` (`section_code`),
    KEY `idx_section_course` (`course_id`),
    KEY `idx_section_professor` (`professor_id`),
    KEY `idx_section_ta1` (`teaching_assistant1_id`),
    KEY `idx_section_ta2` (`teaching_assistant2_id`),
    CONSTRAINT `fk_sections_course`
      FOREIGN KEY (`course_id`) REFERENCES `courses` (`course_id`)
      ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT `fk_sections_professor`
      FOREIGN KEY (`professor_id`) REFERENCES `users` (`user_id`)
      ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT `fk_sections_ta1`
      FOREIGN KEY (`teaching_assistant1_id`) REFERENCES `users` (`user_id`)
      ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT `fk_sections_ta2`
      FOREIGN KEY (`teaching_assistant2_id`) REFERENCES `users` (`user_id`)
      ON DELETE SET NULL ON UPDATE CASCADE
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Course sections/class groups';

  -- Sessions
  CREATE TABLE IF NOT EXISTS `sessions` (
    `session_id` INT NOT NULL AUTO_INCREMENT,
    `section_id` INT NOT NULL,
    `session_date` DATE NOT NULL,
    `start_time` TIME NOT NULL,
    `end_time`   TIME NOT NULL,
    `location`   VARCHAR(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'Classroom or venue',
    `late_threshold_minutes` INT DEFAULT '15' COMMENT 'Minutes after start_time to mark as Late',
    `status` ENUM('PENDING','OPEN','CLOSED') COLLATE utf8mb4_unicode_ci DEFAULT 'PENDING',
    `opened_at` TIMESTAMP NULL DEFAULT NULL COMMENT 'Timestamp when session opened',
    `closed_at` TIMESTAMP NULL DEFAULT NULL COMMENT 'Timestamp when session closed',
    `created_by` INT DEFAULT NULL COMMENT 'User who created the session',
    `created_at` TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`session_id`),
    KEY `idx_section` (`section_id`),
    KEY `idx_session_date` (`session_date`),
    KEY `idx_status` (`status`),
    KEY `idx_section_date` (`section_id`,`session_date`),
    KEY `idx_section_date_status` (`section_id`,`session_date`,`status`),
    CONSTRAINT `fk_sessions_section`
      FOREIGN KEY (`section_id`) REFERENCES `sections` (`section_id`)
      ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT `fk_sessions_created_by`
      FOREIGN KEY (`created_by`) REFERENCES `users` (`user_id`)
      ON DELETE SET NULL ON UPDATE CASCADE
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

  -- Attendance records
  CREATE TABLE IF NOT EXISTS `attendance_records` (
    `attendance_id` INT NOT NULL AUTO_INCREMENT,
    `session_id` INT NOT NULL,
    `student_id` CHAR(8) COLLATE utf8mb4_unicode_ci NOT NULL,
    `status` ENUM('PRESENT','ABSENT','LATE') COLLATE utf8mb4_unicode_ci NOT NULL,
    `marked_at` TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'When attendance was marked',
    `marking_method` ENUM('AUTO','MANUAL') COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'AUTO: face recognition, MANUAL: admin override',
    `confidence_score` DECIMAL(5,2) DEFAULT NULL COMMENT 'Recognition confidence (0-100) for AUTO method only',
    `last_seen` TIMESTAMP NULL DEFAULT NULL COMMENT 'Last detection timestamp for cooldown management',
    `notes` TEXT COLLATE utf8mb4_unicode_ci COMMENT 'Optional notes (e.g., Medical Leave, Late arrival)',
    `marked_by` INT DEFAULT NULL COMMENT 'User who manually marked (NULL for AUTO)',
    `created_at` TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`attendance_id`),
    UNIQUE KEY `uk_session_student_attendance` (`session_id`,`student_id`),
    KEY `idx_attend_session` (`session_id`),
    KEY `idx_attend_student` (`student_id`),
    KEY `idx_attend_status` (`status`),
    KEY `idx_attend_method` (`marking_method`),
    KEY `idx_attend_marked_at` (`marked_at`),
    KEY `idx_attend_session_status` (`session_id`,`status`),
    KEY `idx_attend_marked_by` (`marked_by`),
    CONSTRAINT `fk_attendance_session`
      FOREIGN KEY (`session_id`) REFERENCES `sessions` (`session_id`)
      ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT `fk_attendance_student`
      FOREIGN KEY (`student_id`) REFERENCES `students` (`student_id`)
      ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT `fk_attendance_marked_by`
      FOREIGN KEY (`marked_by`) REFERENCES `users` (`user_id`)
      ON DELETE SET NULL ON UPDATE CASCADE
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

  -- Session roster
  CREATE TABLE IF NOT EXISTS `session_roster` (
    `roster_id`  INT NOT NULL AUTO_INCREMENT,
    `session_id` INT NOT NULL,
    `student_id` CHAR(8) COLLATE utf8mb4_unicode_ci NOT NULL,
    `added_at`   TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    `added_by`   INT DEFAULT NULL COMMENT 'User who added student to roster',
    PRIMARY KEY (`roster_id`),
    UNIQUE KEY `uk_session_student` (`session_id`,`student_id`),
    KEY `idx_roster_session` (`session_id`),
    KEY `idx_roster_student` (`student_id`),
    KEY `idx_roster_added_by` (`added_by`),
    CONSTRAINT `fk_roster_session`
      FOREIGN KEY (`session_id`) REFERENCES `sessions` (`session_id`)
      ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT `fk_roster_student`
      FOREIGN KEY (`student_id`) REFERENCES `students` (`student_id`)
      ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT `fk_roster_added_by`
      FOREIGN KEY (`added_by`) REFERENCES `users` (`user_id`)
      ON DELETE SET NULL ON UPDATE CASCADE
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Session roster for pre-enrolled and dynamically added students';

  -- Section enrolments
  CREATE TABLE IF NOT EXISTS `section_enrolments` (
    `enrolment_id` INT NOT NULL AUTO_INCREMENT,
    `student_id`   CHAR(8) COLLATE utf8mb4_unicode_ci NOT NULL,
    `section_id`   INT NOT NULL,
    `enrolment_date` DATE DEFAULT (curdate()),
    `status` ENUM('ENROLLED','DROPPED','COMPLETED') COLLATE utf8mb4_unicode_ci DEFAULT 'ENROLLED',
    `created_at` TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`enrolment_id`),
    UNIQUE KEY `uk_unique_enrolment` (`student_id`,`section_id`),
    KEY `idx_enrol_student` (`student_id`),
    KEY `idx_enrol_section` (`section_id`),
    KEY `idx_enrol_status` (`status`),
    CONSTRAINT `fk_enrol_student`
      FOREIGN KEY (`student_id`) REFERENCES `students` (`student_id`)
      ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT `fk_enrol_section`
      FOREIGN KEY (`section_id`) REFERENCES `sections` (`section_id`)
      ON DELETE CASCADE ON UPDATE CASCADE
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Student enrolment in course sections';

  -- System settings
  CREATE TABLE IF NOT EXISTS `system_settings` (
    `setting_id` INT NOT NULL AUTO_INCREMENT,
  `setting_key`   VARCHAR(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'e.g., recognition_threshold, attendance_log_path',
    `setting_value` TEXT COLLATE utf8mb4_unicode_ci NOT NULL,
    `data_type` ENUM('STRING','INTEGER','DECIMAL','BOOLEAN','JSON') COLLATE utf8mb4_unicode_ci DEFAULT 'STRING',
    `description` TEXT COLLATE utf8mb4_unicode_ci,
    `is_editable` TINYINT(1) DEFAULT '1' COMMENT 'Prevent modification of critical settings',
    `created_at` TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`setting_id`),
    UNIQUE KEY `uk_setting_key` (`setting_key`),
    KEY `idx_setting_key` (`setting_key`)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Application configuration and settings';

  INSERT INTO `system_settings` (`setting_key`, `setting_value`, `data_type`, `description`)
  VALUES
    ('recognition_threshold', '85.00', 'DECIMAL', 'Minimum confidence for auto-marking'),
    ('attendance_log_path', 'logs/attendance.log', 'STRING', 'Filesystem path to attendance audit log file')
  ON DUPLICATE KEY UPDATE
    `setting_value` = VALUES(`setting_value`),
    `data_type` = VALUES(`data_type`),
    `description` = VALUES(`description`);

  -- Training images used for model building (raw bytes + metadata)
  CREATE TABLE IF NOT EXISTS `training_data` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `student_id` CHAR(8) COLLATE utf8mb4_unicode_ci NOT NULL,
    `filename`   VARCHAR(255) COLLATE utf8mb4_unicode_ci,
    `mime_type`  VARCHAR(100) COLLATE utf8mb4_unicode_ci,
    `image`      MEDIUMBLOB NOT NULL,
    `checksum`   CHAR(64) COLLATE utf8mb4_unicode_ci, -- hex SHA-256
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `is_mask_variant` TINYINT(1) NOT NULL DEFAULT 0,
    `source_training_id` BIGINT DEFAULT NULL,
    `has_real_mask` TINYINT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_training_student`  (`student_id`),
    KEY `idx_training_checksum` (`checksum`),
    KEY `idx_training_source` (`source_training_id`),
    CONSTRAINT `fk_training_student`
      FOREIGN KEY (`student_id`)
      REFERENCES `students`(`student_id`)
      ON DELETE CASCADE
      ON UPDATE CASCADE,
    CONSTRAINT `fk_training_variant_source`
      FOREIGN KEY (`source_training_id`)
      REFERENCES `training_data`(`id`)
      ON DELETE CASCADE
      ON UPDATE CASCADE
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

  -- ============================================
  -- Views (no is_active references)
  -- ============================================

  CREATE VIEW `vw_active_students` AS
  SELECT
    s.student_id,
    s.full_name,
    s.email,
    COUNT(DISTINCT se.section_id)   AS enrolled_sections_count,
    COUNT(DISTINCT fd.face_data_id) AS face_images_count,
    s.enrolment_date,
    s.created_at
  FROM students s
  LEFT JOIN section_enrolments se
    ON s.student_id = se.student_id AND se.status = 'ENROLLED'
  LEFT JOIN face_data fd
    ON s.student_id = fd.student_id
  GROUP BY s.student_id, s.full_name, s.email, s.enrolment_date, s.created_at;

  CREATE VIEW `vw_session_attendance_summary` AS
  SELECT
    sess.session_id,
    c.course_code,
    c.course_name,
    sec.section_code,
    CONCAT(c.course_code,'-',sec.section_code) AS course_section,
    sess.session_date,
    sess.start_time,
    sess.end_time,
    sess.location,
    sess.status AS session_status,
    COUNT(DISTINCT sr.student_id) AS total_roster,
    COUNT(DISTINCT CASE WHEN ar.status = 'PRESENT' THEN ar.student_id END) AS present_count,
    COUNT(DISTINCT CASE WHEN ar.status = 'LATE' THEN ar.student_id END)    AS late_count,
    COUNT(DISTINCT CASE WHEN ar.status = 'ABSENT' THEN ar.student_id END)  AS absent_count,
    COUNT(DISTINCT CASE WHEN ar.status IS NULL THEN sr.student_id END)     AS pending_count,
    ROUND(
      (COUNT(DISTINCT CASE WHEN ar.status IN ('PRESENT','LATE') THEN ar.student_id END) * 100.0)
      / NULLIF(COUNT(DISTINCT sr.student_id), 0)
    , 2) AS attendance_percentage
  FROM sessions sess
  JOIN sections sec ON sess.section_id = sec.section_id
  JOIN courses  c   ON sec.course_id   = c.course_id
  LEFT JOIN session_roster sr ON sess.session_id = sr.session_id
  LEFT JOIN attendance_records ar ON sess.session_id = ar.session_id AND sr.student_id = ar.student_id
  GROUP BY sess.session_id, c.course_code, c.course_name, sec.section_code,
          sess.session_date, sess.start_time, sess.end_time, sess.location, sess.status;

  CREATE VIEW `vw_student_attendance_history` AS
  SELECT
    st.student_id,
    st.full_name,
    c.course_code,
    c.course_name,
    sec.section_code,
    CONCAT(c.course_code,'-',sec.section_code) AS course_section,
    sess.session_date,
    sess.start_time,
    sess.end_time,
    COALESCE(ar.status,'PENDING') AS attendance_status,
    ar.marking_method,
    ar.confidence_score,
    ar.marked_at,
    ar.notes
  FROM students st
  JOIN section_enrolments se ON st.student_id = se.student_id
  JOIN sections          sec ON se.section_id = sec.section_id
  JOIN courses            c  ON sec.course_id = c.course_id
  JOIN sessions         sess ON sec.section_id = sess.section_id
  LEFT JOIN session_roster   sr ON sess.session_id = sr.session_id AND st.student_id = sr.student_id
  LEFT JOIN attendance_records ar ON sess.session_id = ar.session_id AND st.student_id = ar.student_id
  WHERE se.status = 'ENROLLED'
  ORDER BY st.student_id ASC, sess.session_date DESC, sess.start_time DESC;

  -- ============================================
  /*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
  /*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
  /*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
