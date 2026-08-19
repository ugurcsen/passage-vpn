package com.passagevpn.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/** Startup fail-fast check for datasource URL / driver mismatches. */
class DatabaseProfileCheckTest {

  private void run(String url, String driver) {
    DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
    StandardEnvironment env = new StandardEnvironment();
    env.getPropertySources()
        .addFirst(
            new MapPropertySource(
                "test",
                Map.of(
                    "spring.datasource.url", url,
                    "spring.datasource.driver-class-name", driver)));
    beanFactory.registerSingleton("environment", env);
    new DatabaseProfileCheck().postProcessBeanFactory(beanFactory);
  }

  @Test
  void passesWithSqliteUrlAndSqliteDriver() {
    assertThatCode(
            () ->
                run(
                    "jdbc:sqlite:./data/passage.db?journal_mode=WAL&busy_timeout=5000",
                    "org.sqlite.JDBC"))
        .doesNotThrowAnyException();
  }

  @Test
  void passesWithPostgresUrlAndPostgresDriver() {
    assertThatCode(() -> run("jdbc:postgresql://localhost:5432/passage", "org.postgresql.Driver"))
        .doesNotThrowAnyException();
  }

  @Test
  void failsWhenPostgresProfileRunsAgainstSqliteUrl() {
    assertThatThrownBy(
            () ->
                run(
                    "jdbc:sqlite:/var/lib/passage/passage.db?journal_mode=WAL",
                    "org.postgresql.Driver"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("jdbc:sqlite")
        .hasMessageContaining("docker-compose.postgres.yml")
        .hasMessageContaining("PASSAGE_PROFILE=sqlite");
  }

  @Test
  void failsWhenSqliteProfileRunsAgainstPostgresUrl() {
    assertThatThrownBy(() -> run("jdbc:postgresql://db:5432/passage", "org.sqlite.JDBC"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("jdbc:postgresql")
        .hasMessageContaining("PASSAGE_PROFILE=postgres");
  }

  @Test
  void passesWithoutDatasourceUrl() {
    DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
    StandardEnvironment env = new StandardEnvironment();
    beanFactory.registerSingleton("environment", env);
    assertThatCode(() -> new DatabaseProfileCheck().postProcessBeanFactory(beanFactory))
        .doesNotThrowAnyException();
  }
}
