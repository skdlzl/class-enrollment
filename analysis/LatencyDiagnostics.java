package com.jiyun.classenrollment.common.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Diagnostic-only JDBC observer; no business SQL, transaction or locking changes. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name="analysis.enabled", havingValue="true")
public class LatencyDiagnostics {
    @Bean static BeanPostProcessor jdbcObserver(Environment env) {
        return new BeanPostProcessor() {
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (!(bean instanceof HikariDataSource pool)) return bean;
                try { return new Observed(pool, Path.of(env.getRequiredProperty("analysis.folder"))); }
                catch (IOException e) { throw new IllegalStateException(e); }
            }
        };
    }
    static class Observed extends DelegatingDataSource {
        final HikariDataSource pool;
        final BufferedWriter events, metrics;
        final AtomicLong sequence = new AtomicLong();
        final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r,"analysis-pool-sampler"); t.setDaemon(true); return t;
        });
        Observed(HikariDataSource pool, Path folder) throws IOException {
            super(pool); this.pool=pool; Files.createDirectories(folder);
            events=Files.newBufferedWriter(folder.resolve("jdbc.csv"));
            metrics=Files.newBufferedWriter(folder.resolve("pool.csv"));
            events.write("epochMs,connectionId,thread,phase,sqlKind,durationMs,ok\n");
            metrics.write("epochMs,active,idle,pending,total\n");
            sampler.scheduleAtFixedRate(() -> {
                var mx=pool.getHikariPoolMXBean();
                if(mx!=null) try { synchronized(metrics) {
                    metrics.write(System.currentTimeMillis()+","+mx.getActiveConnections()+","+mx.getIdleConnections()+","+mx.getThreadsAwaitingConnection()+","+mx.getTotalConnections()+"\n");
                }} catch(IOException e) { throw new UncheckedIOException(e); }
            },0,50,TimeUnit.MILLISECONDS);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                sampler.shutdownNow();
                try { synchronized(events){ events.close(); } synchronized(metrics){ metrics.close(); } }
                catch(IOException e) { e.printStackTrace(); }
            }));
        }
        @Override public Connection getConnection() throws SQLException { return acquire(null,null); }
        @Override public Connection getConnection(String u,String p) throws SQLException { return acquire(u,p); }
        Connection acquire(String u,String p) throws SQLException {
            boolean observed=Thread.currentThread().getName().contains("-exec-");
            long id=sequence.incrementAndGet(), started=System.nanoTime();
            Connection c;
            try { c=u==null?super.getConnection():super.getConnection(u,p); }
            catch(SQLException e) { if(observed)record(id,"acquire","",started,false); throw e; }
            if(!observed) return c;
            record(id,"acquire","",started,true);
            long held=System.nanoTime();
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(proxy,m,args)->{
                String n=m.getName(); long s=System.nanoTime(); boolean ok=false;
                try {
                    Object result=invoke(c,m,args); ok=true;
                    if((n.equals("prepareStatement")||n.equals("prepareCall")) && result instanceof PreparedStatement ps) {
                        String sql=(String)args[0]; String kind=kind(sql);
                        Class<?> type=result instanceof CallableStatement?CallableStatement.class:PreparedStatement.class;
                        return Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},(p2,method,a2)->{
                            long begin=System.nanoTime(); boolean success=false;
                            try { Object value=invoke(ps,method,a2); success=true; return value; }
                            finally { if(method.getName().startsWith("execute"))record(id,"sql",kind,begin,success); }
                        });
                    }
                    return result;
                } finally {
                    if(n.equals("commit")||n.equals("rollback"))record(id,n,"",s,ok);
                    if(n.equals("close"))record(id,"hold","",held,ok);
                }
            });
        }
        static Object invoke(Object target,Method method,Object[] args) throws Throwable {
            try { return method.invoke(target,args); } catch(InvocationTargetException e){ throw e.getCause(); }
        }
        static String kind(String sql) {
            String s=sql.stripLeading().toLowerCase(Locale.ROOT);
            if(s.startsWith("update courses"))return "COURSE_UPDATE";
            if(s.startsWith("insert into enrollments"))return "ENROLLMENT_INSERT";
            if(s.contains("duplicateenrollment"))return "VALIDATION_SELECT";
            if(s.contains("from students"))return "STUDENT_SELECT";
            if(s.contains("from courses"))return "COURSE_SELECT";
            return "OTHER";
        }
        void record(long id,String phase,String kind,long started,boolean ok) {
            double ms=(System.nanoTime()-started)/1e6;
            try { synchronized(events) { events.write(String.format(Locale.ROOT,"%d,%d,%s,%s,%s,%.3f,%s%n",System.currentTimeMillis(),id,Thread.currentThread().getName(),phase,kind,ms,ok)); } }
            catch(IOException e) { throw new UncheckedIOException(e); }
        }
    }
}
