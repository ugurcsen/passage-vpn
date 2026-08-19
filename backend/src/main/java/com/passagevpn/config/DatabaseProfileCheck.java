package com.passagevpn.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fails startup with an actionable message when the configured datasource URL and driver disagree.
 *
 * <p>The classic footgun: the {@code postgres} Spring profile is active (driver {@code
 * org.postgresql.Driver}) while {@code PASSAGE_DB_URL} still points at the SQLite file pinned by
 * {@code docker-compose.yml} — e.g. {@code PASSAGE_PROFILE=postgres} in {@code .env} with a plain
 * {@code docker compose up}. Without this check the failure surfaces as a cryptic Flyway/Hikari
 * error ("Driver org.postgresql.Driver claims to not accept jdbcUrl, jdbc:sqlite:..."). Runs as a
 * {@link BeanFactoryPostProcessor} so it fires before Flyway is ever instantiated.
 */
@Component
public class DatabaseProfileCheck implements BeanFactoryPostProcessor {

  @Override
  public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
    Environment environment = beanFactory.getBean(Environment.class);
    String url = environment.getProperty("spring.datasource.url", "");
    String driver = environment.getProperty("spring.datasource.driver-class-name", "");
    if (url.isBlank()) {
      return;
    }
    if (url.startsWith("jdbc:sqlite:") && driver.toLowerCase().contains("postgres")) {
      throw new IllegalStateException(
          "Datasource URL is SQLite ("
              + url
              + ") but the driver is "
              + driver
              + " — the 'postgres' Spring profile is active while PASSAGE_DB_URL is pinned to the"
              + " SQLite path by docker-compose.yml. Start the stack with"
              + " 'docker compose -f docker-compose.yml -f docker-compose.postgres.yml up' (or"
              + " reinstall via install.sh --profile=postgres) so the backend receives"
              + " PASSAGE_DB_URL=jdbc:postgresql://..., or set PASSAGE_PROFILE=sqlite in .env to"
              + " keep SQLite.");
    }
    if (url.startsWith("jdbc:postgresql:") && !driver.toLowerCase().contains("postgres")) {
      throw new IllegalStateException(
          "Datasource URL is PostgreSQL ("
              + url
              + ") but the driver is "
              + driver
              + " — the 'sqlite' profile is active. Set PASSAGE_PROFILE=postgres in .env (and"
              + " start via docker-compose.postgres.yml) for PostgreSQL, or point PASSAGE_DB_URL"
              + " at a jdbc:sqlite: URL for SQLite.");
    }
  }
}
