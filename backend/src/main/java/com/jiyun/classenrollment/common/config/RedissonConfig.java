// 이 클래스가 속한 패키지입니다.
package com.jiyun.classenrollment.common.config;


// RedissonClient 객체를 실제로 생성하는 클래스입니다.
import org.redisson.Redisson;

// Redis 명령과 분산락 기능을 사용할 때 주입받는 인터페이스입니다.
import org.redisson.api.RedissonClient;

// Redis 서버 주소, 연결 방식 등을 저장하는 Redisson의 설정 클래스입니다.
import org.redisson.config.Config;

// application.yml에 작성된 설정값을 가져올 때 사용합니다.
import org.springframework.beans.factory.annotation.Value;

// 메서드가 반환하는 객체를 Spring Bean으로 등록할 때 사용합니다.
import org.springframework.context.annotation.Bean;

// 이 클래스가 Spring 설정 클래스라는 것을 표시합니다.
import org.springframework.context.annotation.Configuration;


/*
 * @Configuration
 *
 * Spring Boot가 애플리케이션을 시작할 때
 * 이 클래스를 설정 클래스로 인식하게 합니다.
 *
 * 이 클래스 안의 @Bean 메서드를 실행해
 * 필요한 객체를 생성하고 Spring이 관리하게 합니다.
 */
@Configuration
public class RedissonConfig {


    /*
     * @Bean
     *
     * 이 메서드가 반환하는 RedissonClient 객체를
     * Spring이 관리하는 Bean으로 등록합니다.
     *
     * Bean:
     * Spring이 생성하고 보관하면서 필요한 클래스에
     * 주입해 주는 객체입니다.
     *
     * destroyMethod = "shutdown":
     * Spring Boot가 종료될 때 RedissonClient의 shutdown()
     * 메서드를 자동으로 호출합니다.
     *
     * Redis 연결과 Redisson 내부 스레드를 정리하기 위해 사용합니다.
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(

            /*
             * application.yml에서 redisson.address 값을 가져옵니다.
             *
             * application.yml:
             *
             * redisson:
             *   address: ${REDIS_ADDRESS:redis://localhost:6379}
             *
             * 현재 별도의 REDIS_ADDRESS 환경변수가 없으므로:
             *
             * address = "redis://localhost:6379"
             *
             * 가 됩니다.
             */
            @Value("${redisson.address}") String address
    ) {

        /*
         * Redisson 연결 정보를 담을 설정 객체를 생성합니다.
         *
         * 여기서 Config는 Spring의 설정이 아니라
         * Redisson 라이브러리의 설정 클래스입니다.
         *
         * import:
         * org.redisson.config.Config
         */
        Config config = new Config();


        /*
         * useSingleServer():
         * Redis 서버 한 대에 연결하도록 설정합니다.
         *
         * 현재 Docker에서 Redis 컨테이너 한 대만 실행했으므로
         * Single Server 방식을 사용합니다.
         *
         * setAddress(address):
         * 연결할 Redis 서버 주소를 지정합니다.
         *
         * 실제 전달되는 값:
         * redis://localhost:6379
         */
        config.useSingleServer()
                .setAddress(address);


        /*
         * 위에서 만든 설정으로 실제 RedissonClient 객체를 생성합니다.
         * 이 시점에 Redisson이 Redis 서버와 연결을 구성합니다.
         * 생성된 객체는 @Bean에 의해 Spring이 보관합니다.
         * 다른 클래스에서는 다음과 같이 생성자로 주입받을 수 있습니다.
         *
         * private final RedissonClient redissonClient;
         * public SomeService(RedissonClient redissonClient) {
         *     this.redissonClient = redissonClient;
         * }
         */
        return Redisson.create(config);
    }
}