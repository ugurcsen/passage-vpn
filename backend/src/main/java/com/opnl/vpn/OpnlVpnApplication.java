package com.opnl.vpn;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** OpenVPN management panel backend entrypoint. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class OpnlVpnApplication {

  public static void main(String[] args) {
    SpringApplication.run(OpnlVpnApplication.class, args);
  }
}
