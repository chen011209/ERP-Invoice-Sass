package com.example.erpinvoicesass;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ErpInvoiceSassApplication {

    public static void main(String[] args) {
        SpringApplication.run(ErpInvoiceSassApplication.class, args);
    }

}
