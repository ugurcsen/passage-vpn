package com.passagevpn.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fails startup with an actionable message when the configured datasource URL and driver disagree.
 *
 * <p>The classic footgun: the {@code postgres} Spring profile is active (driver {@code
 * org.postgresql.Driver}) while {@code PASSAGE_DB_URL} still resolves to the SQLite file — e.g.
 * {@code PASSAGE_PROFILE=postgres} in {@code .env} without pointing the URL at a PostgreSQL server.
 * Without this check the failure surfaces as a cryptic Flyway/Hikari error ("Driver
 * org.postgresql.Driver claims to not accept jdbcUrl, jdbc:sqlite:..."). Runs as a {@link
 * BeanFactoryPostProcessor} so it fires before Flyway is ever instantiated.
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
              + " — the 'postgres' Spring profile is active while the datasource URL is not."
              + " Either point PASSAGE_DB_URL at a PostgreSQL server in .env, e.g."
              + " PASSAGE_DB_URL=jdbc:postgresql://host:5432/passage (remote database, no db"
              + " container, plain 'docker compose up'), or start the compose-managed database via"
              + " 'docker compose -f docker-compose.yml -f docker-compose.postgres.yml up' (or"
              + " reinstall via install.sh --profile=postgres), or set"
              + " PASSAGE_PROFILE=sqlite in .env to keep SQLite.");
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
