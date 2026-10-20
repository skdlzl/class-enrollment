package com.jiyun.classenrollment.student.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface StudentRepository extends JpaRepository<Student, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select s from Student s where s.id = :id")
    Optional<Student> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") Long id);

    Optional<Student> findByStudentNumber(String studentNumber);
}
