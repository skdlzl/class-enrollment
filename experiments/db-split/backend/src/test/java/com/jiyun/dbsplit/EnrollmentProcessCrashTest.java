package com.jiyun.dbsplit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual child JVM termination against a live MySQL container. Database and second server stay alive. */
@EnabledIfSystemProperty(named="enrollment.crash",matches="true")
class EnrollmentProcessCrashTest extends EnrollmentSafetyTest {
    final ObjectMapper json=new ObjectMapper();
    final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    final Path directory=Path.of("target","enrollment-crash").toAbsolutePath();
    class Server implements AutoCloseable {
        final Process process;final String base;final Path reached;
        Server(String name)throws Exception {
            Files.createDirectories(directory);
            Path ready=directory.resolve(name+"-ready.json");reached=directory.resolve(name+"-phase.txt");
            Files.deleteIfExists(ready);Files.deleteIfExists(reached);
            Path argfile=Files.createTempFile("enrollment-crash-",".args");
            var values=List.of("-Xms128m","-Xmx256m","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),
                EnrollmentCrashServer.class.getName(),ready.toString(),reached.toString(),"--experiment.mode=A",
                "--experiment.notifications.enabled=false","--spring.datasource.url="+mysql.getJdbcUrl(),
                "--spring.datasource.username="+mysql.getUsername(),"--spring.datasource.password="+mysql.getPassword());
            Files.writeString(argfile,String.join("\n",values.stream().map(NotificationServerProcess::quote).toList()));
            Process child=null;
            try {
                child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),"@"+argfile)
                    .redirectErrorStream(true).redirectOutput(directory.resolve(name+".log").toFile()).start();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);
                while(child.isAlive()&&!Files.exists(ready)&&System.nanoTime()<deadline)Thread.sleep(50);
                assertTrue(child.isAlive()&&Files.exists(ready),"Child startup failed: "+name);
                var state=json.readTree(ready.toFile());assertEquals(child.pid(),state.get("pid").asLong());
                base="http://127.0.0.1:"+state.get("port").asInt();process=child;
            } catch(Throwable e) {if(child!=null)NotificationServerProcess.stop(child);throw e;}
            finally {Files.deleteIfExists(argfile);}
        }
        void awaitPhase(String phase,String requestId)throws Exception {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while(System.nanoTime()<deadline) {
                if(Files.exists(reached)&&Files.readString(reached).equals(phase+"\n"+requestId))return;
                assertTrue(process.isAlive(),"Child exited before fault point");Thread.sleep(25);
            }
            fail("Request did not reach "+phase);
        }
        int kill()throws Exception {
            process.destroyForcibly();assertTrue(process.waitFor(10,TimeUnit.SECONDS));assertFalse(process.isAlive());
            assertNotEquals(0,process.exitValue(),"Expected abnormal process exit");return process.exitValue();
        }
        public void close()throws Exception {NotificationServerProcess.stop(process);}
    }
    CompletableFuture<HttpResponse<String>> post(Server server,EnrollmentService.Request r,String phase)throws Exception {
        return client.sendAsync(HttpRequest.newBuilder(URI.create(server.base+"/enroll"+(phase.isEmpty()?"":"?"+phase)))
            .timeout(Duration.ofSeconds(30)).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(r))).build(),HttpResponse.BodyHandlers.ofString());
    }
    void confirmed(HttpResponse<String> response)throws Exception {
        assertEquals(200,response.statusCode(),response.body());assertEquals("CONFIRMED",json.readTree(response.body()).get("status").asText());
    }
    void totals(int rows,int credits) {
        assertEquals(rows,jdbc.queryForObject("SELECT COUNT(*) FROM enrollment WHERE run_id=? AND status='CONFIRMED'",Integer.class,run));
        assertEquals(rows,jdbc.queryForObject("SELECT SUM(enrolled_count) FROM offered_course WHERE run_id=?",Integer.class,run));
        assertEquals(credits,jdbc.queryForObject("SELECT SUM(enrolled_credits) FROM student_term WHERE run_id=?",Integer.class,run));
        consistent();
    }
    @Test void duplicateRequestsAndRealProcessDeathPreserveEnrollment()throws Exception {
        var results=new ArrayList<Map<String,Object>>();
        try(var survivor=new Server("survivor");var victim=new Server("before-commit")) {
            assertNotEquals(survivor.process.pid(),victim.process.pid());
            var duplicate=request(1,1);
            var first=post(survivor,duplicate,"");var second=post(victim,duplicate,"");
            confirmed(first.get(20,TimeUnit.SECONDS));confirmed(second.get(20,TimeUnit.SECONDS));totals(1,3);
            var newId=post(victim,request(1,1),"").get(20,TimeUnit.SECONDS);
            assertEquals(409,newId.statusCode());assertTrue(newId.body().contains("DUPLICATE_ENROLLMENT"));
            var changed=new EnrollmentService.Request(duplicate.requestId(),run,1,202602,2);
            var conflict=post(victim,changed,"").get(20,TimeUnit.SECONDS);
            assertEquals(409,conflict.statusCode());assertTrue(conflict.body().contains("REQUEST_ID_CONFLICT"));totals(1,3);
            results.add(Map.of("scenario","duplicate-click-and-key-conflict","pids",List.of(survivor.process.pid(),victim.process.pid()),"rows",1,"credits",3,"passed",true));

            var before=request(2,2);var interrupted=post(victim,before,"before-commit");
            victim.awaitPhase("before-commit",before.requestId());assertFalse(interrupted.isDone());
            totals(1,3); // Uncommitted changes are not visible from a separate connection.
            int exit=victim.kill();assertThrows(ExecutionException.class,()->interrupted.get(10,TimeUnit.SECONDS));
            new org.springframework.transaction.support.TransactionTemplate(tx).execute(status->{
                jdbc.queryForList("SELECT student_id FROM student_term WHERE run_id=? AND student_id=2 FOR UPDATE",run);
                totals(1,3); // Lock reacquisition proves the dead connection released its transaction; no partial write persisted.
                return null;
            });
            confirmed(post(survivor,before,"").get(20,TimeUnit.SECONDS));totals(2,6);
            results.add(Map.of("scenario","kill-before-commit-then-retry","killedPid",victim.process.pid(),"exitCode",exit,"rowsAfterRetry",2,"credits",6,"passed",true));

            try(var restarted=new Server("after-commit")) {
                assertNotEquals(victim.process.pid(),restarted.process.pid());
                var after=request(3,3);var lost=post(restarted,after,"after-commit");
                restarted.awaitPhase("after-commit",after.requestId());assertFalse(lost.isDone());totals(3,9);
                int exitAfter=restarted.kill();assertThrows(ExecutionException.class,()->lost.get(10,TimeUnit.SECONDS));
                confirmed(post(survivor,after,"").get(20,TimeUnit.SECONDS));totals(3,9);
                results.add(Map.of("scenario","kill-after-commit-before-response-then-retry","killedPid",restarted.process.pid(),"exitCode",exitAfter,"rowsAfterRetry",3,"credits",9,"passed",true));
            }
            json.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("verification.json").toFile(),Map.of(
                "passed",true,"scope","Real service via test-only HTTP adapter; actual child JVM forcible termination; MySQL remains alive",
                "runId",run,"results",results));
        }
    }
}
