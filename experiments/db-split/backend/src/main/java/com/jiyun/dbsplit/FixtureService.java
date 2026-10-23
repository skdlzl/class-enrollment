package com.jiyun.dbsplit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
@Service
public class FixtureService {
    private final JdbcTemplate jdbc;
    public FixtureService(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Transactional
    public Map<String,Object> create(){
        String run=UUID.randomUUID().toString();jdbc.update("INSERT INTO experiment_run(run_id) VALUES(?)",run);
        List<Object[]> students=new ArrayList<>(),subjects=new ArrayList<>(),courses=new ArrayList<>(),schedules=new ArrayList<>();
        for(int i=1;i<=500;i++)students.add(new Object[]{run,i,"Student "+i,"ACTIVE",18});
        jdbc.batchUpdate("INSERT INTO academic_student(run_id,id,name,status,max_credits) VALUES(?,?,?,?,?)",students);
        // Snapshot is explicit. No A reads are needed during enrollment.
        jdbc.update("INSERT INTO student_term(run_id,student_id,term_id,status,max_credits) SELECT run_id,id,202602,status,max_credits FROM academic_student WHERE run_id=?",run);
        for(int i=1;i<=10000;i++){
            String department=i<=5?"D0":"D"+(i%10);
            int grade=i<=5?1:(i/10)%4+1;
            String name=i<=5?"수강실습 "+i:List.of("자료구조","데이터베이스","운영체제","네트워크").get(i%4)+" "+i;
            subjects.add(new Object[]{run,i,"S"+i,name,"학사 기본 과목 설명 "+i,department});
            courses.add(new Object[]{run,i,202602,i,name,department,grade,3,100});
            schedules.add(new Object[]{run,i,(i-1)%5+1,540,600});
        }
        jdbc.batchUpdate("INSERT INTO subject(run_id,id,code,name,description,department) VALUES(?,?,?,?,?,?)",subjects);
        jdbc.batchUpdate("INSERT INTO offered_course(run_id,id,term_id,subject_id,name,department,grade,credits,capacity) VALUES(?,?,?,?,?,?,?,?,?)",courses);
        jdbc.batchUpdate("INSERT INTO offering_schedule(run_id,course_id,day_no,start_minute,end_minute) VALUES(?,?,?,?,?)",schedules);
        return Map.of("runId",run,"termId",202602,"students",500,"catalogCount",10000,"courseIds",List.of(1,2,3,4,5));
    }
    public Map<String,Object> integrity(String run){
        var courses=jdbc.queryForList("SELECT c.id,c.capacity,c.enrolled_count,(SELECT COUNT(*) FROM enrollment e WHERE e.run_id=c.run_id AND e.course_id=c.id AND e.status='CONFIRMED') actual FROM offered_course c WHERE c.run_id=? AND c.id<=5 ORDER BY c.id",run);
        long mismatch=jdbc.queryForObject("SELECT COUNT(*) FROM offered_course c WHERE c.run_id=? AND (c.enrolled_count<>(SELECT COUNT(*) FROM enrollment e WHERE e.run_id=c.run_id AND e.course_id=c.id AND e.status='CONFIRMED') OR c.enrolled_count>c.capacity)",Long.class,run);
        long credits=jdbc.queryForObject("SELECT COUNT(*) FROM student_term s WHERE s.run_id=? AND (s.enrolled_credits>s.max_credits OR s.enrolled_credits<>COALESCE((SELECT SUM(c.credits) FROM enrollment e JOIN offered_course c ON c.run_id=e.run_id AND c.id=e.course_id WHERE e.run_id=s.run_id AND e.student_id=s.student_id AND e.term_id=s.term_id AND e.status='CONFIRMED'),0))",Long.class,run);
        long conflicts=jdbc.queryForObject("""
          SELECT COUNT(*) FROM enrollment e JOIN enrollment f ON f.run_id=e.run_id AND f.student_id=e.student_id AND f.term_id=e.term_id AND e.course_id<f.course_id
          JOIN offering_schedule a ON a.run_id=e.run_id AND a.course_id=e.course_id
          JOIN offering_schedule b ON b.run_id=f.run_id AND b.course_id=f.course_id AND b.day_no=a.day_no AND a.start_minute<b.end_minute AND b.start_minute<a.end_minute
          WHERE e.run_id=? AND e.status='CONFIRMED' AND f.status='CONFIRMED'
          """,Long.class,run);
        return Map.of("courses",courses,"counterMismatches",mismatch,"creditViolations",credits,"scheduleViolations",conflicts,
          "confirmed",jdbc.queryForObject("SELECT COUNT(*) FROM enrollment WHERE run_id=? AND status='CONFIRMED'",Long.class,run),
          "totalCatalogRows",jdbc.queryForObject("SELECT COUNT(*) FROM offered_course",Long.class),
          "studentsWithMultipleEnrollments",jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT student_id FROM enrollment WHERE run_id=? AND status='CONFIRMED' GROUP BY student_id HAVING COUNT(*)>1) d",Long.class,run));
    }
}
