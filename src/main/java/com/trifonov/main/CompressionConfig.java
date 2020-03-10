package com.trifonov.main;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

@ComponentScan(basePackages = {"com.trifonov.compression"})
@PropertySource(value = "application.properties")
@Configuration
public class CompressionConfig {

}
