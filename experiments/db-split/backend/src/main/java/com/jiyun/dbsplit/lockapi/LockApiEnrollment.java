package com.jiyun.dbsplit.lockapi;

import com.jiyun.dbsplit.*;
import org.redisson.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Isolated opt-in comparison. OFF leaves the historical guarded paths unchanged. */
@Service
public class LockApiEnrollment {
 public enum Strategy { OFF, REDIS, PESSIMISTIC, OPTIMISTIC }
 public static class Unavailable extends RuntimeException {public final String code;public Unavailable(String code){super(code);this.code=code;}}
 static class VersionConflict extends RuntimeException {}
 private final JdbcTemplate jdbc;private final Metrics metrics;private final TransactionTemplate tx;
 private final RedissonClient redis;private final EligibilityGateway gateway;private final Strategy strategy;
 private final String url;private final AtomicInteger inFlight=new AtomicInteger();
 public LockApiEnrollment(JdbcTemplate jdbc,Metrics metrics,PlatformTransactionManager manager,
  ObjectProvider<RedissonClient> clients,Environment env,DatabaseConfig.Mode mode){
  this.jdbc=jdbc;this.metrics=metrics;this.redis=clients.getIfAvailable();
  strategy=Strategy.valueOf(env.getProperty("experiment.lock-api.strategy","OFF"));url=env.getProperty("experiment.lock-api.url","");
  if(strategy!=Strategy.OFF&&(mode!=DatabaseConfig.Mode.B||url.isBlank()||env.getProperty("experiment.notifications.enabled",Boolean.class,false)))
   throw new IllegalArgumentException("Lock/API comparison requires split mode B, explicit eligibility URL and notifications OFF");
  if(strategy==Strategy.REDIS&&redis==null)throw new IllegalArgumentException("Redis client required");
  gateway=new EligibilityGateway(url,metrics);tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
 }
 public boolean enabled(){return strategy!=Strategy.OFF;}
 public int inFlight(){return inFlight.get();}
 public Map<String,Object> state(){return Map.of("strategy",strategy.name(),"url",url,"requestBudgetMs",10000,"apiTimeoutMs",2000,"maxOptimisticAttempts",5,"watchdogMs",30000);}
 public EnrollmentService.Result enroll(EnrollmentService.Request r){
  if(!enabled())throw new IllegalStateException("Comparison disabled");
  if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Facade must run outside caller transaction");
  long start=System.nanoTime(),deadline=start+TimeUnit.SECONDS.toNanos(10);inFlight.incrementAndGet();boolean failed=true;
  try{
   EnrollmentService.Result result;
   if(strategy==Strategy.REDIS)result=locked(key(r,"student",r.studentId()),"studentRedis",deadline,studentLock->{
    var student=student(r,false);var known=known(r);if(known!=null)return known;
    gateway.check(r,deadline);int credits=validate(r,student);
    return locked(key(r,"course",r.courseId()),"courseRedis",deadline,courseLock->transaction(()->{
     budget(deadline);if(!studentLock.isHeldByCurrentThread()||!courseLock.isHeldByCurrentThread())throw new Unavailable("REDIS_OWNERSHIP_LOST");
     var course=course(r,false);capacity(course);write(r,student,course,credits,false);return confirmed(r);
    }));
   });
   else if(strategy==Strategy.PESSIMISTIC)result=transaction(()->{
    var student=student(r,true);var known=known(r);if(known!=null)return known;
    gateway.check(r,deadline);int credits=validate(r,student);var course=course(r,true);capacity(course);
    budget(deadline);write(r,student,course,credits,false);return confirmed(r);
   });
   else result=optimistic(r,deadline);
   failed=false;return result;
  }catch(EnrollmentService.Rejected e){
   if("DUPLICATE_ENROLLMENT".equals(e.code)){var result=known(r);if(result!=null){failed=false;return result;}}
   throw e;
  }finally{inFlight.decrementAndGet();metrics.record("lockApi.request",System.nanoTime()-start,failed);}
 }
 private EnrollmentService.Result optimistic(EnrollmentService.Request r,long deadline){
  for(int attempt=1;attempt<=5;attempt++){
   budget(deadline);metrics.record("optimistic.attempt",0,false);
   var student=student(r,false);var known=known(r);if(known!=null)return known;
   gateway.check(r,deadline);
   try{return transaction(()->{
    budget(deadline);int credits=validate(r,student);var course=course(r,false);capacity(course);
    write(r,student,course,credits,true);return confirmed(r);
   });}catch(VersionConflict conflict){
    metrics.record("optimistic.conflict",0,true);
    if(attempt==5)throw new Unavailable("OPTIMISTIC_RETRY_EXHAUSTED");
    metrics.record("optimistic.retry",0,false);
    try{Thread.sleep(ThreadLocalRandom.current().nextLong(10,31));}
    catch(InterruptedException e){Thread.currentThread().interrupt();throw new Unavailable("REQUEST_INTERRUPTED");}
   }
  }
  throw new AssertionError();
 }
 private <T>T transaction(Supplier<T> work){return metrics.time("enrollment.transactionProxy",()->tx.execute(s->work.get()));}
 private static void budget(long deadline){if(System.nanoTime()>=deadline)throw new Unavailable("REQUEST_BUDGET_EXCEEDED");}
 private static String key(EnrollmentService.Request r,String kind,long id){return "lock-api:"+r.runId()+":"+r.termId()+":"+kind+":"+id;}
 private <T>T locked(String key,String phase,long deadline,java.util.function.Function<RLock,T> action){
  RLock lock;long started=System.nanoTime();boolean acquired=false;
  try{lock=redis.getLock(key);budget(deadline);acquired=lock.tryLock(Math.max(0,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
   if(!acquired)throw new Unavailable("REDIS_LOCK_TIMEOUT");
  }catch(InterruptedException e){Thread.currentThread().interrupt();throw new Unavailable("REDIS_LOCK_INTERRUPTED");}
  catch(Unavailable e){throw e;}catch(RuntimeException e){throw new Unavailable("REDIS_UNAVAILABLE");}
  finally{metrics.record(phase+".wait",System.nanoTime()-started,!acquired);}
  long held=System.nanoTime();
  try{return action.apply(lock);}finally{
   metrics.record(phase+".holdUntilCompletion",System.nanoTime()-held,false);
   try{lock.unlock();}catch(RuntimeException e){metrics.record(phase+".unlockFailure",0,true);}
  }
 }
 private Map<String,Object> student(EnrollmentService.Request r,boolean lock){
  var rows=metrics.time(lock?"studentLock.sql":"studentRead.sql",()->jdbc.queryForList(
   "SELECT status,max_credits,enrolled_credits,version FROM student_term WHERE run_id=? AND student_id=? AND term_id=?"+(lock?" FOR UPDATE":""),r.runId(),r.studentId(),r.termId()));
  if(rows.isEmpty())throw new EnrollmentService.Rejected("STUDENT_NOT_FOUND");if(lock)hold("studentLock");return rows.get(0);
 }
 private Map<String,Object> course(EnrollmentService.Request r,boolean lock){
  var rows=metrics.time(lock?"courseLock.sql":"courseRead.sql",()->jdbc.queryForList(
   "SELECT capacity,enrolled_count,version FROM offered_course WHERE run_id=? AND id=? AND term_id=?"+(lock?" FOR UPDATE":""),r.runId(),r.courseId(),r.termId()));
  if(rows.isEmpty())throw new EnrollmentService.Rejected("COURSE_NOT_FOUND");if(lock)hold("courseLock");return rows.get(0);
 }
 private void hold(String phase){long start=System.nanoTime();TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
  public void afterCompletion(int status){metrics.record(phase+".holdUntilCompletion",System.nanoTime()-start,false);}
 });}
 private static int number(Map<String,Object> row,String key){return ((Number)row.get(key)).intValue();}
 private static void capacity(Map<String,Object> c){if(number(c,"enrolled_count")>=number(c,"capacity"))throw new EnrollmentService.Rejected("COURSE_FULL");}
 private EnrollmentService.Result known(EnrollmentService.Request r){
  var rows=jdbc.queryForList("SELECT run_id,student_id,term_id,course_id,status FROM enrollment WHERE request_id=?",r.requestId());
  if(rows.isEmpty())return null;var k=rows.get(0);
  if(!r.runId().equals(k.get("run_id"))||r.studentId()!=((Number)k.get("student_id")).longValue()||r.termId()!=number(k,"term_id")||r.courseId()!=((Number)k.get("course_id")).longValue())throw new EnrollmentService.Rejected("REQUEST_ID_CONFLICT");
  return new EnrollmentService.Result(r.requestId(),(String)k.get("status"));
 }
 private int validate(EnrollmentService.Request r,Map<String,Object> student){return metrics.time("validation",()->{
  if(!"ACTIVE".equals(student.get("status")))throw new EnrollmentService.Rejected("STUDENT_NOT_ACTIVE");
  var courses=jdbc.queryForList("SELECT credits FROM offered_course WHERE run_id=? AND id=? AND term_id=?",r.runId(),r.courseId(),r.termId());
  if(courses.isEmpty())throw new EnrollmentService.Rejected("COURSE_NOT_FOUND");int credits=number(courses.get(0),"credits");
  if(jdbc.queryForObject("SELECT COUNT(*) FROM enrollment WHERE run_id=? AND student_id=? AND term_id=? AND course_id=?",Integer.class,r.runId(),r.studentId(),r.termId(),r.courseId())>0)throw new EnrollmentService.Rejected("DUPLICATE_ENROLLMENT");
  if(number(student,"enrolled_credits")+credits>number(student,"max_credits"))throw new EnrollmentService.Rejected("CREDIT_LIMIT_EXCEEDED");
  int conflicts=jdbc.queryForObject("""
   SELECT COUNT(*) FROM enrollment e JOIN offering_schedule own ON own.run_id=e.run_id AND own.course_id=e.course_id
   JOIN offering_schedule target ON target.run_id=e.run_id AND target.course_id=? AND target.day_no=own.day_no
   AND target.start_minute<own.end_minute AND own.start_minute<target.end_minute
   WHERE e.run_id=? AND e.student_id=? AND e.term_id=? AND e.status='CONFIRMED'
   """,Integer.class,r.courseId(),r.runId(),r.studentId(),r.termId());
  if(conflicts>0)throw new EnrollmentService.Rejected("SCHEDULE_CONFLICT");return credits;
 });}
 private void write(EnrollmentService.Request r,Map<String,Object> s,Map<String,Object> c,int credits,boolean optimistic){
  long start=System.nanoTime();boolean failed=true;
  try{
   int a=optimistic?jdbc.update("UPDATE student_term SET enrolled_credits=enrolled_credits+?,version=version+1 WHERE run_id=? AND student_id=? AND term_id=? AND version=?",credits,r.runId(),r.studentId(),r.termId(),s.get("version")):
    jdbc.update("UPDATE student_term SET enrolled_credits=enrolled_credits+?,version=version+1 WHERE run_id=? AND student_id=? AND term_id=?",credits,r.runId(),r.studentId(),r.termId());
   if(a!=1)throw new VersionConflict();
   int b=optimistic?jdbc.update("UPDATE offered_course SET enrolled_count=enrolled_count+1,version=version+1 WHERE run_id=? AND id=? AND version=?",r.runId(),r.courseId(),c.get("version")):
    jdbc.update("UPDATE offered_course SET enrolled_count=enrolled_count+1,version=version+1 WHERE run_id=? AND id=?",r.runId(),r.courseId());
   if(b!=1)throw new VersionConflict();
   jdbc.update("INSERT INTO enrollment(request_id,run_id,student_id,term_id,course_id) VALUES(?,?,?,?,?)",r.requestId(),r.runId(),r.studentId(),r.termId(),r.courseId());failed=false;
  }finally{metrics.record("enrollment.write",System.nanoTime()-start,failed);}
 }
 private static EnrollmentService.Result confirmed(EnrollmentService.Request r){return new EnrollmentService.Result(r.requestId(),"CONFIRMED");}
}
