package pro.belbix.ethparser.utils;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import pro.belbix.ethparser.Application;
import pro.belbix.ethparser.utils.UtilsStarter;

@SpringBootApplication
public class AppUtils {

  public static void main(String[] args) {
    SpringApplication.run(Application.class, args).getBean(UtilsStarter.class).startUtils();
  }

}
