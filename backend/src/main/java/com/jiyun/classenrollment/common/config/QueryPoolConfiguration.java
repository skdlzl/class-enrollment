package com.jiyun.classenrollment.common.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/** Same database, separate connection budgets. Only the course catalogue uses the read pool. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="enrollment.query-pool.enabled",havingValue="true")
@EnableConfigurationProperties(DataSourceProperties.class)
public class QueryPoolConfiguration {
    private HikariDataSource create(DataSourceProperties properties, Environment env, boolean catalog) {
        int total=env.getProperty("spring.datasource.hikari.maximum-pool-size",Integer.class,10);
        int reads=env.getProperty("enrollment.query-pool.size",Integer.class,4);
        if(reads<1||reads>=total)throw new IllegalArgumentException("Query pool size must be between 1 and total pool size - 1");
        var pool=properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        Binder.get(env).bind("spring.datasource.hikari",Bindable.ofInstance(pool));
        int size=catalog?reads:total-reads;
        pool.setMaximumPoolSize(size);
        pool.setMinimumIdle(size);
        pool.setPoolName(catalog?"CatalogPool":"EnrollmentPool");
        pool.setReadOnly(catalog);
        return pool;
    }
    @Bean(name="dataSource",destroyMethod="close") @Primary
    HikariDataSource writePool(DataSourceProperties properties,Environment env){return create(properties,env,false);}
    @Bean(name="catalogDataSource",destroyMethod="close")
    HikariDataSource catalogPool(DataSourceProperties properties,Environment env){return create(properties,env,true);}
    @Bean @Primary
    JdbcTemplate jdbcTemplate(@Qualifier("dataSource") HikariDataSource pool){return new JdbcTemplate(pool);}
    @Bean
    JdbcTemplate catalogJdbcTemplate(@Qualifier("catalogDataSource") HikariDataSource pool){return new JdbcTemplate(pool);}
}
