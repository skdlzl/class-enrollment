package com.jiyun.dbsplit.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.UUID;

@Component
public class NotificationStore {
    public record Job(String id,String payload,String owner,int attempts){}
    private final JdbcTemplate jdbc;
    public NotificationStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public List<String> candidates(int limit){
        return jdbc.queryForList("""
            SELECT id FROM notification_outbox
            WHERE (status='PENDING' AND next_attempt<=CURRENT_TIMESTAMP(6))
               OR (status='PROCESSING' AND lease_until<=CURRENT_TIMESTAMP(6))
            ORDER BY created_at,id LIMIT ?
            """,String.class,limit);
    }
    // Each statement commits before the external call. The owner fences stale acknowledgements.
    public Job claim(String id,long leaseMs){
        String owner=UUID.randomUUID().toString();
        int n=jdbc.update("""
            UPDATE notification_outbox SET status='PROCESSING',owner=?,attempts=attempts+1,
              lease_until=TIMESTAMPADD(MICROSECOND,?,CURRENT_TIMESTAMP(6))
            WHERE id=? AND ((status='PENDING' AND next_attempt<=CURRENT_TIMESTAMP(6))
               OR (status='PROCESSING' AND lease_until<=CURRENT_TIMESTAMP(6)))
            """,owner,leaseMs*1000,id);
        if(n==0)return null;
        var rows=jdbc.query("SELECT id,payload,owner,attempts FROM notification_outbox WHERE id=? AND owner=?",
            (r,i)->new Job(r.getString(1),r.getString(2),r.getString(3),r.getInt(4)),id,owner);
        return rows.isEmpty()?null:rows.get(0);
    }
    public boolean sent(Job job){
        return jdbc.update("""
            UPDATE notification_outbox SET status='SENT',sent_at=CURRENT_TIMESTAMP(6),owner=NULL,lease_until=NULL,last_error=NULL
            WHERE id=? AND status='PROCESSING' AND owner=?
            """,job.id(),job.owner())==1;
    }
    public void retry(Job job,String code,boolean permanent,int maxAttempts){
        // REVIEW includes ambiguous outcomes: never claim that the provider did not send.
        String status=permanent||job.attempts()>=maxAttempts?"REVIEW":"PENDING";
        long delay=Math.min(60,1L<<Math.min(job.attempts(),6));
        jdbc.update("""
            UPDATE notification_outbox SET status=?,owner=NULL,lease_until=NULL,last_error=?,
              next_attempt=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6))
            WHERE id=? AND status='PROCESSING' AND owner=?
            """,status,code,delay,job.id(),job.owner());
    }
    public void lockForInline(String id){
        jdbc.queryForList("SELECT id FROM notification_outbox WHERE id=? FOR UPDATE",id);
    }
}
