package com.jiyun.dbsplit.lockapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiyun.dbsplit.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/** Read-only eligibility check. No notification/payment side effects to replay on optimistic retry. */
public class EligibilityGateway {
 private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
 private final String url;private final Metrics metrics;private final ObjectMapper json=new ObjectMapper();
 public EligibilityGateway(String url,Metrics metrics){this.url=url;this.metrics=metrics;}
 public void check(EnrollmentService.Request r,long deadline){
  long started=System.nanoTime();boolean failed=true;metrics.record("eligibility.calls",0,false);
  try{
   long remaining=deadline-System.nanoTime();if(remaining<=0)throw new LockApiEnrollment.Unavailable("REQUEST_BUDGET_EXCEEDED");
   var req=HttpRequest.newBuilder(URI.create(url+"?studentId="+r.studentId()+"&termId="+r.termId()+"&requestId="+r.requestId()))
    .timeout(Duration.ofNanos(Math.min(remaining,Duration.ofSeconds(2).toNanos()))).GET().build();
   var response=http.send(req,HttpResponse.BodyHandlers.ofString());
   if(response.statusCode()!=200)throw new LockApiEnrollment.Unavailable("ELIGIBILITY_API_UNAVAILABLE");
   var data=json.readTree(response.body());
   if(!data.has("eligible")||!data.get("eligible").isBoolean())throw new LockApiEnrollment.Unavailable("ELIGIBILITY_API_INVALID_RESPONSE");
   if(!data.get("eligible").asBoolean())throw new EnrollmentService.Rejected("ELIGIBILITY_DENIED");
   failed=false;
  }catch(InterruptedException e){Thread.currentThread().interrupt();throw new LockApiEnrollment.Unavailable("ELIGIBILITY_INTERRUPTED");}
  catch(EnrollmentService.Rejected|LockApiEnrollment.Unavailable e){throw e;}
  catch(Exception e){throw new LockApiEnrollment.Unavailable("ELIGIBILITY_API_UNAVAILABLE");}
  finally{metrics.record("eligibility.http",System.nanoTime()-started,failed);}
 }
}
