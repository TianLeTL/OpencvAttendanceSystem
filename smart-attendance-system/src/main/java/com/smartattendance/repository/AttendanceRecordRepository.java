package com.smartattendance.repository;

import com.smartattendance.entity.AttendanceRecord;
import com.smartattendance.entity.AttendanceRecord.AttendanceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AttendanceRecordRepository extends JpaRepository<AttendanceRecord, Integer> {

    List<AttendanceRecord> findBySessionSessionId(Integer sessionId);

    Optional<AttendanceRecord> findBySessionSessionIdAndStudentStudentId(Integer sessionId, String studentId);

    long countBySessionSessionId(Integer sessionId);

    long countBySessionSessionIdAndStatus(Integer sessionId, AttendanceStatus status);

    @Query("SELECT ar.student.studentId FROM AttendanceRecord ar WHERE ar.session.sessionId = :sessionId")
    List<String> findStudentIdsBySession(@Param("sessionId") Integer sessionId);

    @Query("""
            SELECT ar.status, COUNT(ar)
            FROM AttendanceRecord ar
            WHERE ar.session.sessionId = :sessionId
            GROUP BY ar.status
            """)
    List<Object[]> countByStatus(@Param("sessionId") Integer sessionId);

    @Query("""
            SELECT ar.session.sessionId, ar.status, COUNT(ar)
            FROM AttendanceRecord ar
            WHERE ar.session.sessionId IN :sessionIds
            GROUP BY ar.session.sessionId, ar.status
            """)
    List<Object[]> countBySessionAndStatus(@Param("sessionIds") List<Integer> sessionIds);

    void deleteBySessionSessionId(Integer sessionId);
}
