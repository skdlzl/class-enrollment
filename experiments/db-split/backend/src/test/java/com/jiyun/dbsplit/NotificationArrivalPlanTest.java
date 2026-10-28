package com.jiyun.dbsplit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class NotificationArrivalPlanTest {
 @Test void percentagesMeanFractionOfUniqueMessagesAndStayBalanced(){
  for(int percent:new int[]{0,50,100}){
   var c=new NotificationArrivalPlan.Condition("test",percent,200,0,2);
   var events=NotificationArrivalPlan.arrivals(1000,c);
   assertEquals(1000+10*percent,events.size());
   assertEquals(events.size()/2,events.stream().filter(x->x.server()==0).count());
   assertEquals(1000,events.stream().filter(x->x.copy()==0).count());
  }
 }
 @Test void delayDoesNotDelayUnrelatedOriginals(){
  var events=NotificationArrivalPlan.arrivals(1000,new NotificationArrivalPlan.Condition("test",100,200,500,2));
  long previous=-1;
  for(var e:events){
   assertTrue(e.dueNanos()>=previous);previous=e.dueNanos();
   assertEquals(e.index()*5_000_000L,e.originalNanos());
   assertEquals(e.originalNanos()+e.copy()*500_000_000L,e.dueNanos());
   assertEquals((e.index()+e.copy())%2,e.server());
  }
  assertEquals(5_000_000L,events.get(1).dueNanos());
 }
 @Test void lowLoadAndMatrixStayExplicit(){
  var conditions=NotificationArrivalPlan.conditions(true,999,4);
  assertEquals(4,conditions.size());assertEquals(10,conditions.get(1).rps());assertEquals(500,conditions.get(2).gapMs());assertEquals(1500,conditions.get(3).gapMs());
  var events=NotificationArrivalPlan.arrivals(1000,conditions.get(2));
  assertEquals(199_800_000_000L,events.get(events.size()-1).originalNanos());
  assertEquals(2000,events.size());
 }
}
