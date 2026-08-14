package com.opnl.vpn.system;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Default restart implementation: replies to the caller first, then closes the application context
 * after a short delay so the HTTP response is flushed. Under Docker the container exits and the
 * restart policy starts a fresh process; on bare metal a supervisor is required.
 */
@Slf4j
@Component
public class DefaultApplicationRestarter implements ApplicationRestarter {

  private final ConfigurableApplicationContext context;

  public DefaultApplicationRestarter(ConfigurableApplicationContext context) {
    this.context = context;
  }

  @Override
  public void scheduleRestart() {
    Thread.ofVirtual()
        .name("backend-restart")
        .start(
            () -> {
              try {
                Thread.sleep(1500);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              log.warn("Backend restart requested via maintenance API; shutting down");
              context.close();
            });
  }
}
