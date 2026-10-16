package com.jiyun.classenrollment.enrollment.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.*;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;

/** Redis is the ingress authority; DB is only accessed by the bounded workers. */
@Component
public class RedisIngressStore {
    private static final String ROOT="enrollment:{ingress}:";
    private static final String RECORDS=ROOT+"records", PAIRS=ROOT+"pairs", READY=ROOT+"ready", TOKENS=ROOT+"tokens";
    private final RedissonClient redis;
    private final ObjectMapper mapper;
    public RedisIngressStore(RedissonClient redis,ObjectMapper mapper){this.redis=redis;this.mapper=mapper;}
    private static final String SUBMIT="""
        local existing=redis.call('HGET',KEYS[2],ARGV[1])
        if existing then return redis.call('HGET',KEYS[1],existing) end
        if redis.call('ZCARD',KEYS[3])>=5000 then return 'FULL' end
        local t=redis.call('TIME'); local now=t[1]*1000+math.floor(t[2]/1000)
        redis.call('HSET',KEYS[1],ARGV[2],ARGV[3])
        redis.call('HSET',KEYS[2],ARGV[1],ARGV[2])
        redis.call('ZADD',KEYS[3],now,ARGV[2])
        redis.call('ZADD',KEYS[4],now,ARGV[2])
        return ARGV[3]
        """;
    private static final String CLAIM="""
        local t=redis.call('TIME'); local now=t[1]*1000+math.floor(t[2]/1000)
        local ids=redis.call('ZRANGEBYSCORE',KEYS[2],'-inf',now,'LIMIT',0,1)
        if #ids==0 then return nil end
        local id=ids[1];local raw=redis.call('HGET',KEYS[1],id)
        if not raw then redis.call('ZREM',KEYS[2],id);return nil end
        local r=cjson.decode(raw);r.status='PROCESSING';r.attempts=r.attempts+1
        r.message='신청 조건을 확인하고 있습니다.'
        local updated=cjson.encode(r)
        redis.call('HSET',KEYS[1],id,updated)
        redis.call('HSET',KEYS[3],id,ARGV[1])
        redis.call('ZADD',KEYS[2],now+tonumber(ARGV[2]),id)
        return updated
        """;
    private static final String FINISH="""
        if redis.call('HGET',KEYS[3],ARGV[1])~=ARGV[2] then return 0 end
        redis.call('HSET',KEYS[1],ARGV[1],ARGV[3])
        redis.call('HDEL',KEYS[3],ARGV[1])
        redis.call('ZREM',KEYS[2],ARGV[1])
        return 1
        """;
    private static final String RETRY="""
        local raw=redis.call('HGET',KEYS[1],ARGV[1]);if not raw then return nil end
        local r=cjson.decode(raw)
        if r.status=='FAILED' then
          local t=redis.call('TIME');local now=t[1]*1000+math.floor(t[2]/1000)
          if redis.call('ZCARD',KEYS[2])>=5000 then return 'FULL' end
          r.status='QUEUED';r.message='다시 접수되었습니다.';r.attempts=0
          raw=cjson.encode(r);redis.call('HSET',KEYS[1],ARGV[1],raw)
          redis.call('ZADD',KEYS[2],now,ARGV[1])
        end
        return raw
        """;
    public EnrollmentTicket submit(long student,long course){
        var ticket=new EnrollmentTicket(0,UUID.randomUUID().toString(),student,course,"",0,"QUEUED",0,null,
            "접수되었습니다. 신청 확정은 처리 후 안내합니다.",Instant.now().toString());
        String raw=redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE,SUBMIT,RScript.ReturnType.VALUE,
            List.of(RECORDS,PAIRS,READY,ROOT+"student:"+student),student+":"+course,ticket.requestId(),encode(ticket));
        return checked(raw);
    }
    public record Claim(EnrollmentTicket ticket,String token){}
    public Claim claim(long leaseMs){
        String token=UUID.randomUUID().toString();
        String raw=redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE,CLAIM,RScript.ReturnType.VALUE,
            List.of(RECORDS,READY,TOKENS),token,leaseMs);
        return raw==null?null:new Claim(decode(raw),token);
    }
    public boolean finish(Claim claim,EnrollmentTicket result){
        Number n=redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE,FINISH,RScript.ReturnType.LONG,
            List.of(RECORDS,READY,TOKENS),claim.ticket().requestId(),claim.token(),encode(result));
        return n.longValue()==1;
    }
    public EnrollmentTicket get(String id){
        String raw=redis.<String,String>getMap(RECORDS,StringCodec.INSTANCE).get(id);
        return checked(raw);
    }
    public List<EnrollmentTicket> forStudent(long student){
        List<String> rows=redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_ONLY,"""
            local ids=redis.call('ZREVRANGE',KEYS[2],0,99);local result={}
            for _,id in ipairs(ids) do
              local raw=redis.call('HGET',KEYS[1],id);if raw then table.insert(result,raw) end
            end
            return result
            """,RScript.ReturnType.LIST,List.of(RECORDS,ROOT+"student:"+student));
        return rows.stream().map(this::decode).toList();
    }
    public EnrollmentTicket retry(String id){
        return checked(redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE,RETRY,RScript.ReturnType.VALUE,
            List.of(RECORDS,READY),id));
    }
    public void defer(Claim claim){
        redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE,"""
            if redis.call('HGET',KEYS[3],ARGV[1])~=ARGV[2] then return 0 end
            local r=cjson.decode(redis.call('HGET',KEYS[1],ARGV[1]))
            r.status='QUEUED';r.message='일시적인 문제로 자동 재시도 중입니다.'
            local t=redis.call('TIME');local now=t[1]*1000+math.floor(t[2]/1000)
            redis.call('HSET',KEYS[1],ARGV[1],cjson.encode(r))
            redis.call('HDEL',KEYS[3],ARGV[1])
            redis.call('ZADD',KEYS[2],now+math.min(30000,r.attempts*5000),ARGV[1])
            return 1
            """,RScript.ReturnType.LONG,List.of(RECORDS,READY,TOKENS),claim.ticket().requestId(),claim.token());
    }
    public boolean hasReady(){
        Number n=redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_ONLY,"""
            local t=redis.call('TIME');local now=t[1]*1000+math.floor(t[2]/1000)
            return redis.call('ZCOUNT',KEYS[1],'-inf',now)
            """,RScript.ReturnType.LONG,List.of(READY));
        return n.longValue()>0;
    }
    public long pending(){return redis.getScoredSortedSet(READY,StringCodec.INSTANCE).size();}
    private EnrollmentTicket checked(String raw){
        if(raw==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"접수 번호를 찾을 수 없습니다.");
        if(raw.equals("FULL"))throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"접수 대기열이 가득 찼습니다. 잠시 후 다시 시도해 주세요.");
        return decode(raw);
    }
    private String encode(EnrollmentTicket t){try{return mapper.writeValueAsString(t);}catch(Exception e){throw new IllegalStateException(e);}}
    private EnrollmentTicket decode(String raw){try{return mapper.readValue(raw,EnrollmentTicket.class);}catch(Exception e){throw new IllegalStateException(e);}}
}
