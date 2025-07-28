package pn.torn.goldeneye;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("pn.torn.goldeneye.repository.mapper")
public class GoldenEyeApplication {
    public static void main(String[] args) {
        SpringApplication.run(GoldenEyeApplication.class, args);
    }
}