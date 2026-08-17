package com.passagevpn;

import com.passagevpn.internal.InternalTlsBootstrap;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** OpenVPN management panel backend entrypoint. */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class PassageVpnApplication {

  public static void main(String[] args) {
    InternalTlsBootstrap.ensure();
    SpringApplication.run(PassageVpnApplication.class, args);
  }
}
