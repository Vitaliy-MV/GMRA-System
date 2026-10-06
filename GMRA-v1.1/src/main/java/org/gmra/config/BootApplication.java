package org.gmra.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ComponentScan("org.gmra")
@EnableScheduling
public class BootApplication{
public final static String DIR_PROJECT = System.getProperty("user.dir")+System.getProperty("file.separator");
public final static String DIRSysFiles = DIR_PROJECT+"Data files/";

public static void main(String[] args) {
		SpringApplication.run(BootApplication.class, args);
	}
}
