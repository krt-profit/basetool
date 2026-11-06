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

package de.greluc.krt.profit.basetool.backend.blueprint.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.blueprint.api.ResolvedProduct;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintNameNormalizer;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Guards against one redirect query per product already resolved from live recipe projections. */
@SpringBootTest
@Transactional
class BlueprintCatalogueReferenceBudgetTest {

  @Autowired private EntityManager entityManager;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private BlueprintImportService imports;
  @Autowired private PersonalBlueprintService personal;
  @Autowired private BlueprintNameNormalizer normalizer;

  @Test
  void importedProductsAndLearnedAliasesDoNotQueryRedirectsPerRow() {
    Fixture fixture = fixture();
    List<BlueprintImportResolutionDto> choices =
        fixture.products().stream()
            .map(product ->
                new BlueprintImportResolutionDto(
                    "External " + product.productName(), product.productKey(), null, null))
            .toList();

    withoutRedirectQueries(() -> {
      BlueprintImportResultDto result = imports.applyImport(fixture.owner(), choices);
      assertThat(result.added()).isEqualTo(12);
      assertThat(result.aliasesLearned()).isEqualTo(12);
    });

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM personal_blueprint WHERE owner_user_id = ?"
                    + " AND output_item_id IS NOT NULL",
                Integer.class,
                fixture.owner()))
        .isEqualTo(12);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM blueprint_external_alias WHERE created_by = ?"
                    + " AND output_item_id IS NOT NULL",
                Integer.class,
                fixture.owner().toString()))
        .isEqualTo(12);
  }

  @Test
  void batchAddedProductsDoNotQueryRedirectsPerRow() {
    Fixture fixture = fixture();

    withoutRedirectQueries(() ->
        assertThat(
                personal
                    .addBatch(
                        fixture.owner(),
                        fixture.products().stream().map(ResolvedProduct::productKey).toList())
                    .added())
            .isEqualTo(12));

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM personal_blueprint WHERE owner_user_id = ?"
                    + " AND output_item_id IS NOT NULL",
                Integer.class,
                fixture.owner()))
        .isEqualTo(12);
  }

  private void withoutRedirectQueries(Runnable work) {
    Statistics statistics =
        entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
    boolean enabled = statistics.isStatisticsEnabled();
    statistics.setStatisticsEnabled(true);
    try {
      long before = redirectQueries(statistics);
      work.run();
      entityManager.flush();
      assertThat(redirectQueries(statistics) - before).isZero();
    } finally {
      statistics.setStatisticsEnabled(enabled);
    }
  }

  private static long redirectQueries(Statistics statistics) {
    return Arrays.stream(statistics.getQueries())
        .filter(query -> query.contains("GameItemRedirect") || query.contains("game_item_redirect"))
        .mapToLong(query -> statistics.getQueryStatistics(query).getExecutionCount())
        .sum();
  }

  private Fixture fixture() {
    UUID owner = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO app_user (id, username, version) VALUES (?, ?, 0)",
        owner,
        "blueprint-budget-" + owner);
    List<ResolvedProduct> products = new ArrayList<>();
    for (int index = 0; index < 12; index++) {
      UUID item = UUID.randomUUID();
      String name = "Budget " + owner + " Product " + index;
      jdbc.update(
          "INSERT INTO game_item (id, name, kind, source_systems) VALUES (?, ?, 'GENERIC',"
              + " 'WIKI_ONLY')",
          item,
          name);
      jdbc.update(
          "INSERT INTO blueprint (id, scwiki_uuid, output_item_id, output_name) VALUES (?, ?, ?,"
              + " ?)",
          UUID.randomUUID(),
          UUID.randomUUID(),
          item,
          name);
      products.add(new ResolvedProduct(normalizer.normalize(name), name, item));
    }
    return new Fixture(owner, products);
  }

  /** Live source projection identities and the owner receiving the blueprints. */
  private record Fixture(UUID owner, List<ResolvedProduct> products) {}
}
