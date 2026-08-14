package com.opnl.vpn;

import com.opnl.vpn.internal.InternalTlsBootstrap;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** OpenVPN management panel backend entrypoint. */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class OpnlVpnApplication {

  public static void main(String[] args) {
    InternalTlsBootstrap.ensure();
    SpringApplication.run(OpnlVpnApplication.class, args);
  }
}
