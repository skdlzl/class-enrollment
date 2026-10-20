package com.jiyun.classenrollment.course.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CourseRepository extends JpaRepository<Course, Long> {
    @Query("select case when c.enrolledCount >= c.capacity then true else false end from Course c where c.id = :courseId")
    Optional<Boolean> findFullById(@Param("courseId") Long courseId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Course c where c.id = :courseId")
    Optional<Course> findByIdForUpdate(@Param("courseId") Long courseId);

    @Query("select cs from CourseSchedule cs where cs.course.id = :courseId")
    List<CourseSchedule> findSchedulesByCourseId(@Param("courseId") Long courseId);
}
