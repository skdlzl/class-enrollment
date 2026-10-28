package com.jiyun.dbsplit;

import java.util.*;

/** Pure planned arrivals; duplicate percentage is percentage of unique notifications duplicated. */
final class NotificationArrivalPlan {
 record Condition(String name,int duplicatePercent,int rps,int gapMs,int copies) {}
 record Arrival(int index,int copy,int server,long originalNanos,long dueNanos) {}
 static List<Condition> conditions(boolean matrix,int rps,int copies){
  return matrix ? List.of(new Condition("DUP50_R200_GAP0",50,200,0,2),
   new Condition("DUP100_R10_GAP0",100,10,0,2),new Condition("DUP100_R5_GAP500",100,5,500,2),
   new Condition("DUP100_R5_GAP1500",100,5,1500,2)) :
   List.of(new Condition("UNIQUE",0,rps,0,1),new Condition("DUPLICATE",100,rps,0,copies));
 }
 static List<Arrival> arrivals(int messages,Condition c){
  if(messages<1||c.rps()<1||c.gapMs()<0||c.duplicatePercent()<0||c.duplicatePercent()>100||c.copies()<1)
   throw new IllegalArgumentException("Invalid arrival plan");
  var events=new ArrayList<Arrival>();
  for(int i=0;i<messages;i++){
   long original=1_000_000_000L*i/c.rps();
   // Spread selected duplicates through the full run; never concentrate them at the beginning.
   boolean duplicate=(i/2+1L)*c.duplicatePercent()/100>(i/2)*(long)c.duplicatePercent()/100;
   int attempts=duplicate?c.copies():1;
   for(int copy=0;copy<attempts;copy++)events.add(new Arrival(i,copy,(i+copy)%2,original,original+copy*c.gapMs()*1_000_000L));
  }
  events.sort(Comparator.comparingLong(Arrival::dueNanos).thenComparingInt(Arrival::index).thenComparingInt(Arrival::copy));
  return List.copyOf(events);
 }
}
