package com.jiyun.classenrollment.enrollment.domain;

import com.jiyun.classenrollment.course.domain.Course;
import com.jiyun.classenrollment.student.domain.Student;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "enrollments", uniqueConstraints = @UniqueConstraint(name = "uk_enrollments_student_course", columnNames = {"student_id", "course_id"}))
public class Enrollment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;
    @Column(name = "enrolled_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime enrolledAt;

    protected Enrollment() {
    }

    private Enrollment(Student student, Course course) {
        this.student = student;
        this.course = course;
    }

    public static Enrollment create(Student student, Course course) {
        return new Enrollment(student, course);
    }

    public Long getId() { return id; }
    public Student getStudent() { return student; }
    public Course getCourse() { return course; }
    public LocalDateTime getEnrolledAt() { return enrolledAt; }
}
