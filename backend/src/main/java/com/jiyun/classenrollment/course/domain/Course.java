package com.jiyun.classenrollment.course.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "courses", uniqueConstraints = @UniqueConstraint(name = "uk_courses_term_code", columnNames = {"academic_year", "semester", "course_code"}))
public class Course {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "course_code", nullable = false, length = 20)
    private String courseCode;
    @Column(nullable = false, length = 100)
    private String name;
    @Column(nullable = false, length = 50)
    private String professor;
    @Column(nullable = false, length = 50)
    private String department;
    @Column(name = "academic_year", nullable = false)
    private int academicYear;
    @Column(nullable = false)
    private int semester;
    @Column(nullable = false)
    private int credits;
    @Column(nullable = false)
    private int capacity;
    @Column(name = "enrolled_count", nullable = false)
    private int enrolledCount;
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    protected Course() {
    }

    public boolean isFull() {
        return enrolledCount >= capacity;
    }

    public void increaseEnrolledCount() {
        if (isFull()) {
            throw new IllegalStateException("수강 정원이 마감되었습니다.");
        }
        enrolledCount++;
    }

    public Long getId() { return id; }
    public String getCourseCode() { return courseCode; }
    public String getName() { return name; }
    public String getProfessor() { return professor; }
    public String getDepartment() { return department; }
    public int getAcademicYear() { return academicYear; }
    public int getSemester() { return semester; }
    public int getCredits() { return credits; }
    public int getCapacity() { return capacity; }
    public int getEnrolledCount() { return enrolledCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
