package com.jiyun.classenrollment.course.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CourseRepository extends JpaRepository<Course, Long> {

    @Query("select cs from CourseSchedule cs where cs.course.id = :courseId")
    List<CourseSchedule> findSchedulesByCourseId(@Param("courseId") Long courseId);

    /*
     * 정원 확인과 신청 인원 증가를 하나의 UPDATE로 처리합니다.
     *
     * enrolled_count가 capacity보다 작을 때만 1을 증가시키므로,
     * 여러 서버가 동시에 실행해도 MySQL의 행 잠금 안에서 조건을 다시 평가합니다.
     *
     * 반환값:
     * 1 -> 좌석 확보 성공
     * 0 -> 이미 정원이 가득 참
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE courses
            SET enrolled_count = enrolled_count + 1
            WHERE id = :courseId
              AND enrolled_count < capacity
            """, nativeQuery = true)
    int increaseEnrolledCountIfAvailable(@Param("courseId") Long courseId);
}
