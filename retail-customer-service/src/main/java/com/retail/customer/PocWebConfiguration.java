package com.retail.customer;

import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.net.InetAddress;
import java.net.UnknownHostException;

@Configuration(proxyBeanMethods = false)
@Profile("poc")
class PocWebConfiguration {
    @Bean
    WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> forceLoopback() throws UnknownHostException {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        return factory -> factory.setAddress(loopback);
    }
}
