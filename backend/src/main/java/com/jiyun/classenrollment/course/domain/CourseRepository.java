package com.jiyun.classenrollment.course.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CourseRepository extends JpaRepository<Course, Long> {
    @Query("select cs from CourseSchedule cs where cs.course.id = :courseId")
    List<CourseSchedule> findSchedulesByCourseId(@Param("courseId") Long courseId);
}
