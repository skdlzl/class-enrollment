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
            UPDATE notification_outbox SET status='PROCESSING',owner=?,attempts=attempts+1,version=version+1,
              lease_until=TIMESTAMPADD(MICROSECOND,?,CURRENT_TIMESTAMP(6))
            WHERE id=? AND ((status='PENDING' AND next_attempt<=CURRENT_TIMESTAMP(6))
               OR (status='PROCESSING' AND lease_until<=CURRENT_TIMESTAMP(6)))
            """,owner,leaseMs*1000,id);
        if(n==0)return null;
        var rows=jdbc.query("SELECT id,payload,owner,attempts FROM notification_outbox WHERE id=? AND owner=?",
            (r,i)->new Job(r.getString(1),r.getString(2),r.getString(3),r.getInt(4)),id,owner);
        return rows.isEmpty()?null:rows.get(0);
    }
    public Long eligibleVersion(String id){
        var versions=jdbc.queryForList("""
            SELECT version FROM notification_outbox WHERE id=?
            AND ((status='PENDING' AND next_attempt<=CURRENT_TIMESTAMP(6))
              OR (status='PROCESSING' AND lease_until<=CURRENT_TIMESTAMP(6)))
            """,Long.class,id);
        return versions.isEmpty()?null:versions.get(0);
    }
    // Compare-and-set commits before HTTP. A loser must never call the provider.
    public Job claimVersion(String id,long version,long leaseMs){
        String owner=UUID.randomUUID().toString();
        int n=jdbc.update("""
            UPDATE notification_outbox SET status='PROCESSING',owner=?,attempts=attempts+1,
              version=version+1,lease_until=TIMESTAMPADD(MICROSECOND,?,CURRENT_TIMESTAMP(6))
            WHERE id=? AND version=? AND ((status='PENDING' AND next_attempt<=CURRENT_TIMESTAMP(6))
              OR (status='PROCESSING' AND lease_until<=CURRENT_TIMESTAMP(6)))
            """,owner,leaseMs*1000,id,version);
        if(n==0)return null;
        return jdbc.queryForObject("SELECT id,payload,owner,attempts FROM notification_outbox WHERE id=? AND owner=?",
            (rs,i)->new Job(rs.getString(1),rs.getString(2),rs.getString(3),rs.getInt(4)),id,owner);
    }
    public boolean sent(Job job){
        return jdbc.update("""
            UPDATE notification_outbox SET version=version+1,status='SENT',sent_at=CURRENT_TIMESTAMP(6),owner=NULL,lease_until=NULL,last_error=NULL
            WHERE id=? AND status='PROCESSING' AND owner=?
            """,job.id(),job.owner())==1;
    }
    public void retry(Job job,String code,boolean permanent,int maxAttempts){
        // REVIEW includes ambiguous outcomes: never claim that the provider did not send.
        String status=permanent||job.attempts()>=maxAttempts?"REVIEW":"PENDING";
        long delay=Math.min(60,1L<<Math.min(job.attempts(),6));
        jdbc.update("""
            UPDATE notification_outbox SET version=version+1,status=?,owner=NULL,lease_until=NULL,last_error=?,
              next_attempt=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6))
            WHERE id=? AND status='PROCESSING' AND owner=?
            """,status,code,delay,job.id(),job.owner());
    }
    public void lockForInline(String id){
        jdbc.queryForList("SELECT id FROM notification_outbox WHERE id=? FOR UPDATE",id);
    }
}
