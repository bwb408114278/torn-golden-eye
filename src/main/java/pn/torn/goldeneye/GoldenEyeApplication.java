package pn.torn.goldeneye;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@MapperScan("pn.torn.goldeneye.repository.mapper")
@EnableCaching
public class GoldenEyeApplication {
    public static void main(String[] args) {
        SpringApplication.run(GoldenEyeApplication.class, args);
    }
}