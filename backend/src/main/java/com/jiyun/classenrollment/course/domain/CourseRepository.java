package com.jiyun.classenrollment.course.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CourseRepository extends JpaRepository<Course, Long> {
    // Scalar query bypasses a Course entity already held in the persistence context.
    @Query("select case when c.enrolledCount >= c.capacity then true else false end "
            + "from Course c where c.id = :courseId")
    Optional<Boolean> findFullStatusById(@Param("courseId") Long courseId);

    @Query("select cs from CourseSchedule cs where cs.course.id = :courseId")
    List<CourseSchedule> findSchedulesByCourseId(@Param("courseId") Long courseId);
}

