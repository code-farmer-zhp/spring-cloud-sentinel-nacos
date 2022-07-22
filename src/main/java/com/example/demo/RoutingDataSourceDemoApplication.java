package com.example.demo;

import com.example.demo.sentinel.SentinelTest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.util.Random;

@SpringBootApplication
@EnableTransactionManagement
public class RoutingDataSourceDemoApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext run = SpringApplication.run(RoutingDataSourceDemoApplication.class, args);
        SentinelTest bean = run.getBean(SentinelTest.class);
        while (true) {
            bean.helloWord();
            try {
                Thread.sleep(new Random().nextInt(2000));
            } catch (InterruptedException e) {
                Thread.interrupted();
            }
        }
    }
}
