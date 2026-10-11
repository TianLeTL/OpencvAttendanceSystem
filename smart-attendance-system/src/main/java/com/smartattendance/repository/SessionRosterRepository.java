package com.smartattendance.repository;

import com.smartattendance.entity.SessionRoster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SessionRosterRepository extends JpaRepository<SessionRoster, Integer> {

    List<SessionRoster> findBySessionSessionId(Integer sessionId);

    long countBySessionSessionId(Integer sessionId);

    boolean existsBySessionSessionIdAndStudentStudentId(Integer sessionId, String studentId);

    Optional<SessionRoster> findBySessionSessionIdAndStudentStudentId(Integer sessionId, String studentId);

    @Query("""
            SELECT sr FROM SessionRoster sr
            JOIN FETCH sr.student st
            WHERE sr.session.sessionId = :sessionId
            ORDER BY st.fullName ASC
            """)
    List<SessionRoster> findRosterWithStudent(@Param("sessionId") Integer sessionId);

    @Query("""
            SELECT sr.session.sessionId, COUNT(sr)
            FROM SessionRoster sr
            WHERE sr.session.sessionId IN :sessionIds
            GROUP BY sr.session.sessionId
            """)
    List<Object[]> countBySession(@Param("sessionIds") List<Integer> sessionIds);

    void deleteBySessionSessionId(Integer sessionId);
}
