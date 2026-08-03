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

package de.greluc.krt.profit.basetool.backend.db;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.StreamUtils;

/**
 * Verifies the V228 seed that maps the German ammo-capacity spelling onto the English catalogue
 * product (#1485, REQ-INV-021).
 *
 * <p>The test executes the <b>actual migration file</b> read from the classpath rather than a
 * transcribed copy of its statement. A seed migration is otherwise untestable: it runs once against
 * an empty catalogue when the test schema is built, long before any fixture exists, so asserting on
 * "what the seed produced" would assert on nothing. Re-running the real file against fixtures tests
 * the shipped SQL and proves its idempotency in the same step — a transcribed copy would only prove
 * that the copy works.
 */
@SpringBootTest
class V228GermanAmmoAliasSeedMigrationTest {

  /** The shipped migration, executed verbatim so the test cannot drift from the artifact. */
  private static final String MIGRATION_PATH =
      "db/migration/V228__seed_german_ammo_capacity_blueprint_aliases.sql";

  /** Fixture ids, namespaced so cleanup can never touch unrelated rows. */
  private static final UUID LOWERCASE_CAP = UUID.fromString("ffff0228-0000-0000-0000-000000000001");

  private static final UUID UPPERCASE_CAP = UUID.fromString("ffff0228-0000-0000-0000-000000000002");
  private static final UUID SECOND_VARIANT =
      UUID.fromString("ffff0228-0000-0000-0000-000000000003");
  private static final UUID NON_AMMO = UUID.fromString("ffff0228-0000-0000-0000-000000000004");
  private static final UUID SOFT_DELETED = UUID.fromString("ffff0228-0000-0000-0000-000000000005");

  @MockitoBean private RoleRepository roleRepository;
  @MockitoBean private SquadronRepository squadronRepository;

  @Autowired private DataSource dataSource;

  private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    cleanup();
  }

  @AfterEach
  void tearDown() {
    cleanup();
  }

  /** Removes the fixtures and every alias they could have produced. */
  private void cleanup() {
    jdbc.update("DELETE FROM blueprint_external_alias WHERE product_name LIKE 'V228 %'");
    jdbc.update(
        "DELETE FROM blueprint WHERE id IN (?,?,?,?,?)",
        LOWERCASE_CAP,
        UPPERCASE_CAP,
        SECOND_VARIANT,
        NON_AMMO,
        SOFT_DELETED);
  }

  /**
   * Inserts one catalogue blueprint.
   *
   * @param id the row id
   * @param outputName the product display name the seed derives from
   * @param scwikiKey the wiki key, used only for the deterministic variant ordering
   * @param deleted whether the row is soft-deleted (and must therefore be skipped)
   */
  private void insertBlueprint(UUID id, String outputName, String scwikiKey, boolean deleted) {
    jdbc.update(
        """
        INSERT INTO blueprint (id, scwiki_uuid, scwiki_key, output_name, scwiki_deleted_at)
        VALUES (?, ?, ?, ?, ?)
        """,
        id,
        UUID.randomUUID(),
        scwikiKey,
        outputName,
        deleted ? new java.sql.Timestamp(System.currentTimeMillis()) : null);
  }

  /** Executes the shipped migration file against the current database. */
  private void runMigration() {
    try {
      jdbc.execute(
          StreamUtils.copyToString(
              new ClassPathResource(MIGRATION_PATH).getInputStream(), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException("migration " + MIGRATION_PATH + " not on the classpath", e);
    }
  }

  /**
   * Reads the seeded aliases produced from the fixtures.
   *
   * @return the alias rows, ordered by external name
   */
  private List<Map<String, Object>> seededAliases() {
    return jdbc.queryForList(
        """
        SELECT external_name, product_key, product_name, source_system, created_by
          FROM blueprint_external_alias
         WHERE product_name LIKE 'V228 %'
         ORDER BY external_name
        """);
  }

  @Test
  void shouldMapTheGermanCapacitySuffixOntoTheEnglishCatalogueProduct() {
    insertBlueprint(LOWERCASE_CAP, "V228 S71 Rifle Magazine (30 cap)", "v228-a", false);

    runMigration();

    assertThat(seededAliases())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.get("external_name")).isEqualTo("V228 S71 Rifle Magazine (30 Schuss)");
              // Exactly BlueprintNameNormalizer's output, so the alias dereferences to the master.
              assertThat(row.get("product_key")).isEqualTo("v228 s71 rifle magazine (30 cap)");
              assertThat(row.get("product_name")).isEqualTo("V228 S71 Rifle Magazine (30 cap)");
              // Not a new localisation source: BlueprintImportService looks up SCMDB only.
              assertThat(row.get("source_system")).isEqualTo("SCMDB");
              assertThat(row.get("created_by")).isEqualTo("system");
            });
  }

  @Test
  void shouldHandleTheUppercaseCapSpellingTheEnglishCatalogueAlsoUses() {
    // The English global.ini is itself inconsistent: both "(15 cap)" and "(15 Cap)" occur.
    insertBlueprint(UPPERCASE_CAP, "V228 P8-AR Rifle Magazine (15 Cap)", "v228-b", false);

    runMigration();

    assertThat(seededAliases())
        .singleElement()
        .satisfies(
            row ->
                assertThat(row.get("external_name"))
                    .isEqualTo("V228 P8-AR Rifle Magazine (15 Schuss)"));
  }

  @Test
  void shouldEmitOneAliasPerProductEvenWithSeveralCatalogueVariants() {
    // Two wiki rows for the same product name must not violate the unique index on the alias.
    insertBlueprint(LOWERCASE_CAP, "V228 C54 SMG Magazine (50 cap)", "v228-a", false);
    insertBlueprint(SECOND_VARIANT, "V228 C54 SMG Magazine (50 cap)", "v228-c", false);

    runMigration();

    assertThat(seededAliases()).hasSize(1);
  }

  @Test
  void shouldSkipProductsWithoutACapacitySuffixAndSoftDeletedOnes() {
    insertBlueprint(NON_AMMO, "V228 Arclight Pistol", "v228-d", false);
    insertBlueprint(SOFT_DELETED, "V228 Retired Magazine (99 cap)", "v228-e", true);

    runMigration();

    assertThat(seededAliases()).isEmpty();
  }

  @Test
  void shouldBeIdempotentSoARerunNeitherDuplicatesNorFails() {
    insertBlueprint(LOWERCASE_CAP, "V228 FS-9 Magazine (75 cap)", "v228-a", false);

    runMigration();
    runMigration();

    assertThat(seededAliases()).hasSize(1);
  }

  @Test
  void shouldNeverOverwriteACuratedAliasForTheSameSpelling() {
    // A user- or admin-resolved alias is the authority; the seed must yield to it (V176's
    // case-folded unique index is what ON CONFLICT DO NOTHING keys on).
    insertBlueprint(LOWERCASE_CAP, "V228 Gallant Rifle Battery (45 cap)", "v228-a", false);
    jdbc.update(
        """
        INSERT INTO blueprint_external_alias
              (id, source_system, external_name, product_key, product_name, created_by)
        VALUES (?, 'SCMDB', 'V228 Gallant Rifle Battery (45 Schuss)', 'curated-key',
                'V228 curated', 'some-user-sub')
        """,
        UUID.randomUUID());

    runMigration();

    assertThat(seededAliases())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.get("product_key")).isEqualTo("curated-key");
              assertThat(row.get("created_by")).isEqualTo("some-user-sub");
            });
  }
}
