package com.smartattendance.repository;

import com.smartattendance.entity.TrainingData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface TrainingDataRepository extends JpaRepository<TrainingData, Long> {

    /**
     * Find all training data for a specific student
     */
    List<TrainingData> findByStudentId(String studentId);

    /**
     * Find non mask-variant training data for a specific student
     */
    List<TrainingData> findByStudentIdAndMaskVariantFalse(String studentId);

    /**
     * Find mask-variant training data for a specific student
     */
    List<TrainingData> findByStudentIdAndMaskVariantTrue(String studentId);

    /**
     * Find training data by checksum
     */
    Optional<TrainingData> findByChecksum(String checksum);

    /**
     * Check if checksum already exists
     */
    boolean existsByChecksum(String checksum);

    /**
     * Find training data by filename
     */
    List<TrainingData> findByFilename(String filename);

    /**
     * Find training data by student ID and filename
     */
    Optional<TrainingData> findByStudentIdAndFilename(String studentId, String filename);

    /**
     * Delete all training data for a specific student
     */
    @Modifying
    @Query("DELETE FROM TrainingData td WHERE td.studentId = :studentId")
    void deleteByStudentId(@Param("studentId") String studentId);

    /**
     * Count training data for a specific student
     */
    long countByStudentId(String studentId);

    long countByStudentIdAndMaskVariantFalse(String studentId);

    /**
     * Find training data by MIME type
     */
    List<TrainingData> findByMimeType(String mimeType);

    /**
     * Check if student has any training data
     */
    boolean existsByStudentId(String studentId);

    /**
     * Find training data by student ID with checksum (for duplicate checking)
     */
    @Query("SELECT td FROM TrainingData td WHERE td.studentId = :studentId AND td.checksum = :checksum")
    Optional<TrainingData> findByStudentIdAndChecksum(@Param("studentId") String studentId, @Param("checksum") String checksum);

    /**
     * Find the earliest training data entry for a student (used for profile thumbnails)
     */
    Optional<TrainingData> findFirstByStudentIdOrderByCreatedAtAsc(String studentId);

    /**
     * Check if a mask variant exists for a source training image
     */
    boolean existsBySourceTrainingId(Long sourceTrainingId);

    /**
     * Delete all mask variants linked to a source training image
     */
    @Modifying
    @Transactional
    void deleteBySourceTrainingId(Long sourceTrainingId);

    /**
     * Update all training data rows to a new student ID.
     */
    @Modifying
    @Transactional
    @Query("UPDATE TrainingData td SET td.studentId = :newStudentId WHERE td.studentId = :currentStudentId")
    void updateStudentId(@Param("currentStudentId") String currentStudentId,
                         @Param("newStudentId") String newStudentId);
}
