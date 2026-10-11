package com.smartattendance.repository;

import com.smartattendance.entity.Session;
import com.smartattendance.entity.Session.SessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SessionRepository extends JpaRepository<Session, Integer> {

    List<Session> findBySectionSectionId(Integer sectionId);

    List<Session> findByStatus(SessionStatus status);

    List<Session> findBySessionDate(LocalDate sessionDate);

    List<Session> findBySessionDateBetween(LocalDate startDate, LocalDate endDate);

    @Query("""
            SELECT COUNT(s) > 0
            FROM Session s
            WHERE s.section.sectionId = :sectionId
              AND s.sessionDate = :sessionDate
              AND (:excludeSessionId IS NULL OR s.sessionId <> :excludeSessionId)
              AND s.startTime < :endTime
              AND s.endTime > :startTime
            """)
    boolean existsOverlappingSession(@Param("sectionId") Integer sectionId,
                                     @Param("sessionDate") LocalDate sessionDate,
                                     @Param("startTime") LocalTime startTime,
                                     @Param("endTime") LocalTime endTime,
                                     @Param("excludeSessionId") Integer excludeSessionId);

    @Query("SELECT s FROM Session s " +
            "JOIN FETCH s.section sec " +
            "JOIN FETCH sec.course " +
            "ORDER BY s.sessionDate DESC, s.startTime DESC")
    List<Session> findAllWithSectionAndCourse();

    @Query("SELECT s FROM Session s " +
            "JOIN FETCH s.section sec " +
            "JOIN FETCH sec.course " +
            "WHERE s.sessionId = :sessionId")
    Optional<Session> findByIdWithSectionAndCourse(@Param("sessionId") Integer sessionId);

    @Query("""
            SELECT s FROM Session s
            WHERE s.status <> :closedStatus
              AND (s.sessionDate < :today
                   OR (s.sessionDate = :today AND s.endTime <= :nowTime))
            """)
    List<Session> findExpiredSessions(@Param("today") LocalDate today,
                                      @Param("nowTime") LocalTime nowTime,
                                      @Param("closedStatus") SessionStatus closedStatus);

    boolean existsBySectionSectionIdAndSessionDateAndStartTimeAndEndTime(Integer sectionId,
                                                                          LocalDate sessionDate,
                                                                          LocalTime startTime,
                                                                          LocalTime endTime);

    @Query("""
            SELECT s FROM Session s
            JOIN FETCH s.section sec
            JOIN FETCH sec.course c
            WHERE sec.professor.userId = :professorUserId
              AND s.sessionDate = :sessionDate
              AND (:excludeSessionId IS NULL OR s.sessionId <> :excludeSessionId)
              AND s.startTime < :endTime
              AND s.endTime > :startTime
            """)
    List<Session> findProfessorOverlaps(@Param("professorUserId") Integer professorUserId,
                                        @Param("sessionDate") LocalDate sessionDate,
                                        @Param("startTime") LocalTime startTime,
                                        @Param("endTime") LocalTime endTime,
                                        @Param("excludeSessionId") Integer excludeSessionId);
}
