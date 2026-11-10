/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.greluc.krt.profit.basetool.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.greluc.krt.profit.basetool.testsupport.containers.TestImages;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Verifies restricted-owner migration, retained evidence and exact concurrent physical exclusion.
 */
@Testcontainers
@Timeout(90)
class V380MaterialReceiptMigrationTest {
  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
          DockerImageName.parse(TestImages.POSTGRES).asCompatibleSubstituteFor("postgres"));

  @TempDir Path directory;

  @Test
  void restrictedOwnerMigratesThrough380AndFlywayValidatesCanonicalLineEndings() throws Exception {
    OwnerDatabase database = ownerDatabase();
    Path raw = Files.createDirectory(directory.resolve("raw"));
    Path canonical = Files.createDirectory(directory.resolve("canonical-lf"));
    copyMigrations(raw, canonical);
    Flyway original = flyway(database, "filesystem:" + raw.toString().replace('\\', '/'), "380");
    original.migrate();
    assertThat(original.info().current().getVersion().getVersion()).isEqualTo("380");
    assertRestrictedOwnerAndInstalledGuard(database);
    Map<String, Integer> applied = checksums(database);

    Flyway normalized =
        flyway(database, "filesystem:" + canonical.toString().replace('\\', '/'), "380");
    var validation = normalized.validateWithResult();
    assertThat(validation.validationSuccessful).isTrue();
    assertThat(validation.invalidMigrations).isEmpty();
    assertThat(checksums(database)).isEqualTo(applied);
    StringBuilder evidence = new StringBuilder();
    for (String version : new String[] {"379", "380"}) {
      String name =
          version.equals("379")
              ? "V379__material_provision_truthful_quantity.sql"
              : "V380__material_receipt_quantity_provenance.sql";
      evidence
          .append("V")
          .append(version)
          .append(" raw SHA256=")
          .append(sha256(raw.resolve(name)))
          .append(" canonicalLF SHA256=")
          .append(sha256(canonical.resolve(name)))
          .append(" Flyway checksum=")
          .append(applied.get(version))
          .append(System.lineSeparator());
    }
    Files.writeString(directory.resolve("validation-proof.txt"), evidence, StandardCharsets.UTF_8);
    System.out.println("Migration evidence directory: " + directory);
    System.out.print(evidence);
  }

  @Test
  void legacyCreditRemainsOnlyItsKnownSubsetAndQualityFloor() throws Exception {
    OwnerDatabase database = ownerDatabase();
    flyway(database, "classpath:db/migration", "377").migrate();
    UUID receipt = UUID.randomUUID();
    UUID origin = UUID.randomUUID();
    try (Connection connection = database.connect()) {
      insertCredit(connection, receipt, false);
      insertFragment(connection, receipt, origin, 0L, 3000L);
    }
    flyway(database, "classpath:db/migration", "379").migrate();
    try (Connection connection = database.connect();
        Statement sql = connection.createStatement();
        ResultSet row =
            sql.executeQuery(
                "SELECT quantity_amount, supplied_quality FROM material_provision_credit")) {
      assertThat(row.next()).isTrue();
      assertThat(row.getDouble(1)).isEqualTo(3.0);
      assertThat(row.getInt(2)).isEqualTo(650);
    }

    flyway(database, "classpath:db/migration", "380").migrate();
    try (Connection connection = database.connect();
        Statement sql = connection.createStatement();
        ResultSet row =
            sql.executeQuery(
                "SELECT amount, quantity_amount, supplied_quality, evidence,"
                    + " jsonb_array_length(quantity_fragments),"
                    + " (quantity_fragments->0->>'fromQuanta')::bigint,"
                    + " (quantity_fragments->0->>'toQuanta')::bigint,"
                    + " attributed_fragments = quantity_fragments,"
                    + " quantity_fragments->0->>'origin'"
                    + " FROM material_provision_credit")) {
      assertThat(row.next()).isTrue();
      assertThat(row.getDouble(1)).isEqualTo(3.0);
      assertThat(row.getDouble(2)).isEqualTo(3.0);
      assertThat(row.getInt(3)).isEqualTo(650);
      assertThat(row.getString(4)).isEqualTo("CREDITED_SUBSET");
      assertThat(row.getInt(5)).isEqualTo(1);
      assertThat(row.getLong(6)).isZero();
      assertThat(row.getLong(7)).isEqualTo(3000L);
      assertThat(row.getBoolean(8)).isTrue();
      assertThat(UUID.fromString(row.getString(9))).isEqualTo(origin);
      assertThat(row.next()).isFalse();
    }
  }

  @Test
  void overlappingIntervalsWaitThenFailWhileAdjacentQuantityCommitsIndependently()
      throws Exception {
    OwnerDatabase database = ownerDatabase();
    flyway(database, "classpath:db/migration", "380").migrate();
    UUID firstReceipt = UUID.randomUUID();
    UUID overlappingReceipt = UUID.randomUUID();
    UUID adjacentReceipt = UUID.randomUUID();
    UUID origin = UUID.randomUUID();
    try (Connection setup = database.connect()) {
      insertCredit(setup, firstReceipt, true);
      insertCredit(setup, overlappingReceipt, true);
      insertCredit(setup, adjacentReceipt, true);
    }
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      try (Connection first = database.connect();
          Connection second = database.connect();
          Connection adjacent = database.connect()) {
        first.setAutoCommit(false);
        second.setAutoCommit(false);
        adjacent.setAutoCommit(false);
        int firstPid = backendPid(first);
        int secondPid = backendPid(second);
        insertFragment(first, firstReceipt, origin, 0L, 3000L);
        Future<SQLException> overlap =
            executor.submit(() -> {
              try {
                insertFragment(second, overlappingReceipt, origin, 1000L, 2000L);
                second.commit();
                return null;
              } catch (SQLException failure) {
                second.rollback();
                return failure;
              }
            });
        try {
          await()
              .atMost(Duration.ofSeconds(15))
              .untilAsserted(() -> assertThat(blockedBy(database, secondPid, firstPid)).isTrue());
          try (Statement timeout = adjacent.createStatement()) {
            timeout.execute("SET LOCAL statement_timeout = '5s'");
          }
          insertFragment(adjacent, adjacentReceipt, origin, 3000L, 4000L);
          adjacent.commit();
          assertThat(overlap.isDone()).isFalse();
          first.commit();
          SQLException refusal = overlap.get(15, TimeUnit.SECONDS);
          assertNotNull(refusal);
          assertThat(refusal.getSQLState()).isEqualTo("23P01");
        } finally {
          first.rollback();
        }
      }
    }
    try (Connection connection = database.connect();
        Statement sql = connection.createStatement();
        ResultSet row = sql.executeQuery("SELECT count(*) FROM material_provision_fragment")) {
      assertThat(row.next()).isTrue();
      assertThat(row.getLong(1)).isEqualTo(2L);
    }
  }

  private static OwnerDatabase ownerDatabase() throws SQLException {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String username = "migration_owner_" + suffix;
    String databaseName = "material_receipt_" + suffix;
    String password = UUID.randomUUID().toString();
    try (Connection bootstrap =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement sql = bootstrap.createStatement()) {
      sql.execute(
          "CREATE ROLE "
              + username
              + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '"
              + password
              + "'");
      sql.execute("CREATE DATABASE " + databaseName + " OWNER " + username);
    }
    String url =
        POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + databaseName);
    return new OwnerDatabase(url, username, password);
  }

  private static Flyway flyway(OwnerDatabase database, String location, String target) {
    return Flyway.configure()
        .dataSource(database.url(), database.username(), database.password())
        .locations(location)
        .target(target)
        .load();
  }

  private static void copyMigrations(Path raw, Path canonical) throws Exception {
    Resource[] migrations =
        new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/V*.sql");
    assertThat(migrations).isNotEmpty();
    for (Resource migration : migrations) {
      String filename = Objects.requireNonNull(migration.getFilename());
      byte[] bytes;
      try (InputStream input = migration.getInputStream()) {
        bytes = input.readAllBytes();
      }
      Files.write(raw.resolve(filename), bytes);
      Files.writeString(
          canonical.resolve(filename),
          new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n"),
          StandardCharsets.UTF_8);
    }
  }

  private static void assertRestrictedOwnerAndInstalledGuard(OwnerDatabase database)
      throws Exception {
    try (Connection connection = database.connect();
        Statement sql = connection.createStatement()) {
      try (ResultSet row =
          sql.executeQuery(
              "SELECT rolsuper, rolcreatedb, rolcreaterole,"
                  + " has_database_privilege(current_user, current_database(), 'CREATE'),"
                  + " current_setting('server_version_num')::int / 10000"
                  + " FROM pg_roles WHERE rolname = current_user")) {
        assertThat(row.next()).isTrue();
        assertThat(row.getBoolean(1)).isFalse();
        assertThat(row.getBoolean(2)).isFalse();
        assertThat(row.getBoolean(3)).isFalse();
        assertThat(row.getBoolean(4)).isTrue();
        assertThat(row.getInt(5)).isEqualTo(18);
      }
      try (ResultSet row =
          sql.executeQuery("SELECT extname FROM pg_extension WHERE extname = 'btree_gist'")) {
        assertThat(row.next()).isTrue();
        assertThat(row.getString(1)).isEqualTo("btree_gist");
      }
      try (ResultSet row =
          sql.executeQuery(
              "SELECT contype, pg_get_constraintdef(oid) FROM pg_constraint"
                  + " WHERE conrelid = 'material_provision_fragment'::regclass"
                  + " AND conname = 'excl_material_provision_physical_once'")) {
        assertThat(row.next()).isTrue();
        assertThat(row.getString(1)).isEqualTo("x");
        assertThat(row.getString(2)).contains("EXCLUDE USING gist", "origin WITH =", "WITH &&");
      }
    }
  }

  private static Map<String, Integer> checksums(OwnerDatabase database) throws SQLException {
    Map<String, Integer> values = new LinkedHashMap<>();
    try (Connection connection = database.connect();
        Statement sql = connection.createStatement();
        ResultSet row =
            sql.executeQuery(
                "SELECT version, checksum FROM flyway_schema_history WHERE version IN ('379',"
                    + " '380') AND success ORDER BY version")) {
      while (row.next()) {
        values.put(row.getString(1), row.getInt(2));
      }
    }
    assertThat(values).containsOnlyKeys("379", "380");
    return values;
  }

  private static String sha256(Path file) throws Exception {
    return HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
  }

  private static void insertCredit(Connection connection, UUID receipt, boolean extended)
      throws SQLException {
    String extraColumns = extended ? ", quantity_amount, supplied_quality" : "";
    String extraValues = extended ? ", 3, 650" : "";
    try (PreparedStatement insert =
        connection.prepareStatement(
            "INSERT INTO material_provision_credit (id, job_order_id, claim_id, participation_id,"
                + " claiming_org_unit_id, material_id, quality_tier_id, source_row_id, amount"
                + extraColumns
                + ") VALUES (?, gen_random_uuid(), gen_random_uuid(), gen_random_uuid(),"
                + " gen_random_uuid(), gen_random_uuid(), (SELECT id FROM quality_tier"
                + " WHERE min_quality = 650 ORDER BY id LIMIT 1), gen_random_uuid(), 3"
                + extraValues
                + ")")) {
      insert.setObject(1, receipt);
      assertThat(insert.executeUpdate()).isEqualTo(1);
    }
  }

  private static void insertFragment(
      Connection connection, UUID receipt, UUID origin, long from, long to) throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            "INSERT INTO material_provision_fragment (credit_id, origin, from_quanta, to_quanta)"
                + " VALUES (?, ?, ?, ?)")) {
      insert.setObject(1, receipt);
      insert.setObject(2, origin);
      insert.setLong(3, from);
      insert.setLong(4, to);
      assertThat(insert.executeUpdate()).isEqualTo(1);
    }
  }

  private static int backendPid(Connection connection) throws SQLException {
    try (Statement sql = connection.createStatement();
        ResultSet row = sql.executeQuery("SELECT pg_backend_pid()")) {
      assertThat(row.next()).isTrue();
      return row.getInt(1);
    }
  }

  private static boolean blockedBy(OwnerDatabase database, int waiting, int blocking)
      throws SQLException {
    try (Connection connection = database.connect();
        PreparedStatement query =
            connection.prepareStatement(
                "SELECT wait_event_type = 'Lock' AND ? = ANY(pg_blocking_pids(pid))"
                    + " FROM pg_stat_activity WHERE pid = ?")) {
      query.setInt(1, blocking);
      query.setInt(2, waiting);
      try (ResultSet row = query.executeQuery()) {
        return row.next() && row.getBoolean(1);
      }
    }
  }

  /**
   * Connection details for one disposable non-superuser-owned migration database.
   *
   * @param url the isolated container database URL
   * @param username its restricted owner
   * @param password a generated throwaway login value
   */
  private record OwnerDatabase(String url, String username, String password) {
    private Connection connect() throws SQLException {
      return DriverManager.getConnection(url, username, password);
    }
  }
}
