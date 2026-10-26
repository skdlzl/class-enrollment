package com.jiyun.dbsplit.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiyun.dbsplit.EnrollmentService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

@Component
public class NotificationOutbox {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final boolean enabled;
    public NotificationOutbox(JdbcTemplate jdbc,ObjectMapper json,
            @Value("${experiment.notifications.enabled:false}") boolean enabled){
        this.jdbc=jdbc;this.json=json;this.enabled=enabled;
    }
    public void enqueue(EnrollmentService.Request request,String event){
        if(!enabled)return;
        if(!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Outbox must share the enrollment transaction");
        String key=UUID.nameUUIDFromBytes((request.requestId()+":"+event).getBytes(StandardCharsets.UTF_8)).toString();
        try{
            String payload=json.writeValueAsString(Map.of("requestId",request.requestId(),"runId",request.runId(),
                "studentId",request.studentId(),"courseId",request.courseId(),"termId",request.termId(),"event",event));
            jdbc.update("INSERT INTO notification_outbox(id,request_id,run_id,event_type,payload) VALUES(?,?,?,?,?)",
                key,request.requestId(),request.runId(),event,payload);
        }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
    }
}
