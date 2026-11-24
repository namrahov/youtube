package az.youtube;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class YoutubeApplication {

    public static void main(String[] args) {
        SpringApplication.run(YoutubeApplication.class, args);
    }

}
