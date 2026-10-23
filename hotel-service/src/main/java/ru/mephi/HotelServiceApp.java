package ru.mephi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
public class HotelServiceApp {
    public static void main(String[] args) {
        SpringApplication.run(HotelServiceApp.class);
    }
}
