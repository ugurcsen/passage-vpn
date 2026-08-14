package com.opnl.vpn.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * The PostgreSQL profile applies its own migration set (db/migration-postgresql) so Boolean
 * defaults can use TRUE/FALSE literals. This test keeps that set in version parity with the
 * SQLite base set so no migration is silently missing on Postgres.
 */
class MigrationParityTest {

  private static final String PG_LOCATION = "db/migration-postgresql";

  private Set<String> versions(String location) throws IOException {
    var resolver = new PathMatchingResourcePatternResolver();
    var resources = resolver.getResources("classpath:" + location + "/V*.sql");
    assertThat(resources.length).as(location).isPositive();
    return java.util.Arrays.stream(resources)
        .map(org.springframework.core.io.Resource::getFilename)
        .map(n -> n.substring(1, n.indexOf("__")))
        .collect(Collectors.toSet());
  }

  @Test
  void postgresMigrationSetMatchesBaseVersions() throws IOException {
    Set<String> base = versions("db/migration");
    Set<String> pg = versions("db/migration-postgresql");

    assertThat(pg).as("Postgres migration versions").isEqualTo(base);
  }

  @Test
  void everyMigrationDeclaresPortableSqlTypes() throws IOException {
    var resolver = new PathMatchingResourcePatternResolver();
    var resources = resolver.getResources("classpath:db/migration-postgresql/V*.sql");
    for (var resource : resources) {
      String sql = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      String name = resource.getFilename();
      // SQLite-only idioms must never leak into the portable set.
      assertThat(sql.toUpperCase())
          .as(name)
          .doesNotContain("AUTOINCREMENT", "INSERT OR", "ON CONFLICT", "PRAGMA", "WITHOUT ROWID");
    }
  }

  @Test
  void postgresBooleanDefaultsUseLiterals() throws IOException {
    var resolver = new PathMatchingResourcePatternResolver();
    var resources = resolver.getResources("classpath:db/migration-postgresql/V*.sql");
    for (var resource : resources) {
      String sql = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      String name = resource.getFilename();
      List<String> lines =
          java.util.Arrays.stream(sql.split("\n")).map(String::trim).filter(l -> l.contains("BOOLEAN")).toList();
      for (String line : lines) {
        assertThat(line.toUpperCase())
            .as(name + ": " + line)
            .doesNotContain("DEFAULT 1", "DEFAULT 0");
      }
    }
  }
}
