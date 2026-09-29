package com.jiyun.classenrollment.course.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CourseRepository extends JpaRepository<Course, Long> {

    @Query("select cs from CourseSchedule cs where cs.course.id = :courseId")
    List<CourseSchedule> findSchedulesByCourseId(@Param("courseId") Long courseId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE courses
            SET enrolled_count = enrolled_count + 1
            WHERE id = :courseId
              AND enrolled_count < capacity
            """, nativeQuery = true)
    int incrementEnrolledCountIfAvailable(@Param("courseId") Long courseId);
}
