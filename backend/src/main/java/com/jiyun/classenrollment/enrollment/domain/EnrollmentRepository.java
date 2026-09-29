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

    /*
     * 중복 신청, 현재 신청 학점, 시간표 충돌을 DB 한 번의 왕복으로 조회합니다.
     * 기존 개별 조회 메서드는 단위 비교와 다른 기능에서 재사용할 수 있도록 유지합니다.
     */
    @Query(value = """
            SELECT
                EXISTS (
                    SELECT 1
                    FROM enrollments duplicate_enrollment
                    WHERE duplicate_enrollment.student_id = :studentId
                      AND duplicate_enrollment.course_id = :courseId
                ) AS duplicateEnrollment,
                COALESCE((
                    SELECT SUM(enrolled_course.credits)
                    FROM enrollments student_enrollment
                    JOIN courses enrolled_course
                      ON enrolled_course.id = student_enrollment.course_id
                    WHERE student_enrollment.student_id = :studentId
                ), 0) AS currentCredits,
                EXISTS (
                    SELECT 1
                    FROM enrollments student_enrollment
                    JOIN course_schedules existing_schedule
                      ON existing_schedule.course_id = student_enrollment.course_id
                    JOIN course_schedules requested_schedule
                      ON requested_schedule.course_id = :courseId
                     AND requested_schedule.day_of_week = existing_schedule.day_of_week
                     AND existing_schedule.start_time < requested_schedule.end_time
                     AND existing_schedule.end_time > requested_schedule.start_time
                    WHERE student_enrollment.student_id = :studentId
                ) AS scheduleConflict
            """, nativeQuery = true)
    EnrollmentValidationSummary findValidationSummary(
            @Param("studentId") Long studentId,
            @Param("courseId") Long courseId
    );
}
