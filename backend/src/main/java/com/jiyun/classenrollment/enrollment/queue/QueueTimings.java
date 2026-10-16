package com.jiyun.classenrollment.enrollment.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/** Cumulative completed-call wall times; includes errors, excludes queue residence time. */
final class QueueTimings {
    private static final Logger log=LoggerFactory.getLogger(QueueTimings.class);
    private final ConcurrentHashMap<String,Sample> samples=new ConcurrentHashMap<>();
    void record(String phase,long nanos) {
        samples.computeIfAbsent(phase,ignored->new Sample()).add(nanos);
    }
    void logSnapshot() {
        new TreeMap<>(samples).forEach((phase,sample)-> {
            long[] s=sample.snapshot();
            if(s[0]==0) return;
            log.info("QUEUE_TIMING scope=cumulative phase={} calls={} totalMs={} meanMs={} maxMs={}",
                phase,s[0],ms(s[1]),ms(s[1]/s[0]),ms(s[2]));
        });
    }
    private static String ms(long nanos) {return String.format(Locale.ROOT,"%.3f",nanos/1_000_000.0);}
    private static final class Sample {
        private long count,total,max;
        synchronized void add(long nanos) {count++;total+=nanos;max=Math.max(max,nanos);}
        synchronized long[] snapshot() {return new long[]{count,total,max};}
    }
}
