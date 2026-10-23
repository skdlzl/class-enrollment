package com.jiyun.dbsplit;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class DatabaseConfig {
    public enum Mode { A, B0, B }
    @Bean Mode experimentMode(Environment env) {
        return Mode.valueOf(env.getProperty("experiment.mode", "A"));
    }
    private HikariDataSource pool(Environment env, String prefix, String name) {
        var ds = new HikariDataSource();
        ds.setPoolName(name);
        ds.setJdbcUrl(env.getProperty(prefix + ".url", env.getRequiredProperty("spring.datasource.url")));
        ds.setUsername(env.getProperty(prefix + ".username", env.getRequiredProperty("spring.datasource.username")));
        ds.setPassword(env.getProperty(prefix + ".password", env.getRequiredProperty("spring.datasource.password")));
        ds.setMaximumPoolSize(10);
        ds.setMinimumIdle(10);
        ds.setConnectionTimeout(10000);
        ds.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        ds.setConnectionInitSql("SET SESSION innodb_lock_wait_timeout=10");
        return ds;
    }
    @Bean @Primary HikariDataSource enrollmentDataSource(Environment env, Mode mode) {
        return pool(env, "spring.datasource", mode == Mode.A ? "sharedPool" : "enrollmentPool");
    }
    @Bean @org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("'${experiment.mode:A}' != 'A'")
    HikariDataSource academicDataSource(Environment env, Mode mode) {
        if(mode == Mode.B && env.getProperty("experiment.academic.url") == null)
            throw new IllegalArgumentException("B requires an explicit academic database URL");
        var ds = pool(env, mode == Mode.B ? "experiment.academic" : "spring.datasource", "academicPool");
        if(mode == Mode.B && ds.getJdbcUrl().equals(env.getRequiredProperty("spring.datasource.url")))
            throw new IllegalArgumentException("B must use separate database endpoints");
        return ds;
    }
    @Bean @Primary JdbcTemplate enrollmentJdbc(@Qualifier("enrollmentDataSource") HikariDataSource ds) {
        return new JdbcTemplate(ds);
    }
    @Bean JdbcTemplate academicJdbc(Mode mode, @Qualifier("enrollmentDataSource") HikariDataSource shared,
            org.springframework.beans.factory.ObjectProvider<HikariDataSource> sources) {
        var ds = mode == Mode.A ? shared : sources.stream().filter(x -> x.getPoolName().equals("academicPool")).findFirst().orElseThrow();
        return new JdbcTemplate(ds);
    }
    @Bean @Primary DataSourceTransactionManager transactionManager(@Qualifier("enrollmentDataSource") HikariDataSource ds) {
        return new DataSourceTransactionManager(ds);
    }
    @Bean TransactionTemplate academicTransaction(@Qualifier("academicJdbc") JdbcTemplate jdbc) {
        return new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }
    @Bean(initMethod="migrate") Flyway enrollmentMigration(@Qualifier("enrollmentDataSource") HikariDataSource ds, Mode mode) {
        return Flyway.configure().dataSource(ds).locations(mode == Mode.B ? "classpath:db/enrollment" : "classpath:db/migration").load();
    }
    @Bean(initMethod="migrate") @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="experiment.mode",havingValue="B")
    Flyway academicMigration(@Qualifier("academicDataSource") HikariDataSource ds) {
        return Flyway.configure().dataSource(ds).locations("classpath:db/academic").load();
    }
}
