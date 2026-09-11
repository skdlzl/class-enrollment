package com.jiyun.classenrollment.enrollment.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.DayOfWeek;
import java.time.LocalTime;

public interface EnrollmentRepository extends JpaRepository<Enrollment, Long> {
    boolean existsByStudentIdAndCourseId(Long studentId, Long courseId);

    @Query("""
            select coalesce(sum(c.credits), 0)
            from Enrollment e
            join e.course c
            where e.student.id = :studentId
            """)
    int sumCreditsByStudentId(@Param("studentId") Long studentId);

    @Query("""
            select count(cs)
            from Enrollment e
            join e.course c
            join CourseSchedule cs on cs.course = c
            where e.student.id = :studentId
              and cs.dayOfWeek = :dayOfWeek
              and cs.startTime < :endTime
              and cs.endTime > :startTime
            """)
    long countScheduleConflicts(
            @Param("studentId") Long studentId,
            @Param("dayOfWeek") DayOfWeek dayOfWeek,
            @Param("startTime") LocalTime startTime,
            @Param("endTime") LocalTime endTime
    );
}
