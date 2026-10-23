package com.jiyun.dbsplit;
import org.springframework.beans.factory.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

@Service
public class FixtureService {
    private final JdbcTemplate jdbc, academic;
    private final TransactionTemplate enrollmentTx, academicTx;
    private final String policy;
    public FixtureService(JdbcTemplate jdbc,@Qualifier("academicJdbc") JdbcTemplate academic,
            PlatformTransactionManager transactionManager,@Qualifier("academicTransaction") TransactionTemplate academicTx,
            @Value("${experiment.dataset-policy:append}") String policy){
        this.jdbc=jdbc;this.academic=academic;this.academicTx=academicTx;
        this.enrollmentTx=new TransactionTemplate(transactionManager);this.policy=policy;
        if(!List.of("append","fixed").contains(policy))throw new IllegalArgumentException("Unknown dataset policy");
    }
    private Map<String,Object> descriptor(String run){
        return Map.of("runId",run,"termId",202602,"students",500,"catalogCount",10000,
            "courseIds",List.of(1,2,3,4,5),"datasetPolicy",policy);
    }
    public Map<String,Object> create(){
        return enrollmentTx.execute(tx->{
            if(policy.equals("fixed")){
                jdbc.update("INSERT IGNORE INTO fixture_control(id,status) VALUES(1,'EMPTY')");
                var state=jdbc.queryForMap("SELECT run_id,status FROM fixture_control WHERE id=1 FOR UPDATE");
                if("READY".equals(state.get("status")))return descriptor((String)state.get("run_id"));
                if(jdbc.queryForObject("SELECT COUNT(*) FROM offered_course",Long.class)>0
                    || jdbc.queryForObject("SELECT COUNT(*) FROM student_term",Long.class)>0
                    || academic.queryForObject("SELECT COUNT(*) FROM subject",Long.class)>0
                    || academic.queryForObject("SELECT COUNT(*) FROM academic_student",Long.class)>0)
                    throw new IllegalStateException("Fixed dataset requires a new empty comparison volume");
            }
            String run=UUID.randomUUID().toString();
            // Provisioning only: academic commit and enrollment commit are NOT a distributed transaction.
            // On partial provisioning failure keep the volume for inspection and start a new session.
            academicTx.executeWithoutResult(txA->{
                List<Object[]> students=new ArrayList<>(),subjects=new ArrayList<>();
                for(int i=1;i<=500;i++)students.add(new Object[]{run,i,"Student "+i,"ACTIVE",18});
                academic.batchUpdate("INSERT INTO academic_student(run_id,id,name,status,max_credits) VALUES(?,?,?,?,?)",students);
                for(int i=1;i<=10000;i++)subjects.add(new Object[]{run,i,"S"+i,courseName(i),"학사 기본 과목 설명 "+i,department(i)});
                academic.batchUpdate("INSERT INTO subject(run_id,id,code,name,description,department) VALUES(?,?,?,?,?,?)",subjects);
            });
            jdbc.update("INSERT INTO experiment_run(run_id) VALUES(?)",run);
            List<Object[]> snapshots=new ArrayList<>();
            for(var row:academic.queryForList("SELECT id,status,max_credits FROM academic_student WHERE run_id=? ORDER BY id",run))
                snapshots.add(new Object[]{run,row.get("id"),202602,row.get("status"),row.get("max_credits")});
            jdbc.batchUpdate("INSERT INTO student_term(run_id,student_id,term_id,status,max_credits) VALUES(?,?,?,?,?)",snapshots);
            List<Object[]> courses=new ArrayList<>(),schedules=new ArrayList<>();
            for(int i=1;i<=10000;i++){
                courses.add(new Object[]{run,i,202602,i,courseName(i),department(i),i<=5?1:(i/10)%4+1,3,100});
                schedules.add(new Object[]{run,i,(i-1)%5+1,540,600});
            }
            jdbc.batchUpdate("INSERT INTO offered_course(run_id,id,term_id,subject_id,name,department,grade,credits,capacity) VALUES(?,?,?,?,?,?,?,?,?)",courses);
            jdbc.batchUpdate("INSERT INTO offering_schedule(run_id,course_id,day_no,start_minute,end_minute) VALUES(?,?,?,?,?)",schedules);
            if(policy.equals("fixed"))jdbc.update("UPDATE fixture_control SET run_id=?,status='READY' WHERE id=1",run);
            return descriptor(run);
        });
    }
    private String department(int i){return i<=5?"D0":"D"+(i%10);}
    private String courseName(int i){return i<=5?"수강실습 "+i:List.of("자료구조","데이터베이스","운영체제","네트워크").get(i%4)+" "+i;}
    public Map<String,Object> reset(String run){
        if(!policy.equals("fixed"))throw new IllegalStateException("Reset is only allowed for fixed comparison datasets");
        return enrollmentTx.execute(tx->{
            var state=jdbc.queryForMap("SELECT run_id,status FROM fixture_control WHERE id=1 FOR UPDATE");
            if(!run.equals(state.get("run_id"))||!"READY".equals(state.get("status")))throw new IllegalArgumentException("Unknown fixed dataset");
            // Caller must stop load and verify BOTH servers are idle before this preparation operation.
            jdbc.queryForList("SELECT student_id FROM student_term WHERE run_id=? ORDER BY student_id FOR UPDATE",run);
            jdbc.queryForList("SELECT id FROM offered_course WHERE run_id=? ORDER BY id FOR UPDATE",run);
            jdbc.update("DELETE FROM enrollment WHERE run_id=?",run);
            jdbc.update("UPDATE student_term SET enrolled_credits=0 WHERE run_id=?",run);
            jdbc.update("UPDATE offered_course SET enrolled_count=0 WHERE run_id=?",run);
            return descriptor(run);
        });
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
        var result=new LinkedHashMap<String,Object>(Map.of("courses",courses,"counterMismatches",mismatch,"creditViolations",credits,"scheduleViolations",conflicts,
          "confirmed",jdbc.queryForObject("SELECT COUNT(*) FROM enrollment WHERE run_id=? AND status='CONFIRMED'",Long.class,run),
          "totalCatalogRows",jdbc.queryForObject("SELECT COUNT(*) FROM offered_course",Long.class),
          "studentsWithMultipleEnrollments",jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT student_id FROM enrollment WHERE run_id=? AND status='CONFIRMED' GROUP BY student_id HAVING COUNT(*)>1) d",Long.class,run)));
        result.put("totalSubjectRows",academic.queryForObject("SELECT COUNT(*) FROM subject",Long.class));
        result.put("totalStudentRows",jdbc.queryForObject("SELECT COUNT(*) FROM student_term",Long.class));
        result.put("runId",run);return result;
    }
}
