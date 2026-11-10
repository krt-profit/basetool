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

package de.greluc.krt.profit.basetool.backend.controller;

import static de.greluc.krt.profit.basetool.backend.support.JobOrderParticipationFixtures.persistedParticipation;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTierRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.MaterialClaim;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderParticipationRepository;
import de.greluc.krt.profit.basetool.backend.joborder.internal.MaterialClaimRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyFixtures;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Checks startup ending and replay against synthetic historical material promises. */
@SpringBootTest
class ProfitHierarchyStartupIntegrationTest {

  @Autowired private JobOrderParticipationRepository participations;

  @Autowired private ApplicationContext context;
  @Autowired private TransactionTemplate transactions;
  @Autowired private OrgUnitRepository units;
  @Autowired private JobOrderRepository orders;
  @Autowired private MaterialRepository materials;
  @Autowired private MaterialClaimRepository claims;
  @Autowired private QualityTierRepository tiers;
  @Autowired private JdbcTemplate jdbc;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void startupEndsOnlyIneligibleLegacyPromisesWithHistoryAndAudit(boolean inactive)
      throws Exception {
    Fixture fixture = transactions.execute(_ -> seed(inactive));
    try {
      List<Map<String, Object>> facts = claimFacts(fixture.order());
      ApplicationRunner startup =
          context.getBean("jobOrderEligibilityStartup", ApplicationRunner.class);

      startup.run(new DefaultApplicationArguments());

      List<Map<String, Object>> ended = claimFacts(fixture.order());
      Map<String, Object> validBefore =
          facts.stream()
              .filter(row -> row.get("id").equals(fixture.validClaim()))
              .findFirst()
              .orElseThrow();
      assertThat(ended)
          .filteredOn(row -> row.get("id").equals(fixture.validClaim()))
          .containsExactly(validBefore);
      assertThat(stableFacts(ended)).containsExactlyInAnyOrderElementsOf(stableFacts(facts));
      assertThat(ended)
          .filteredOn(row -> row.get("id").equals(fixture.invalidClaim()))
          .singleElement()
          .satisfies(row -> {
            assertThat(row).containsEntry("amount", 0.0);
            List<Map<String, Object>> changes = JsonPath.read(row.get("changes").toString(), "$");
            assertThat(changes)
                .singleElement()
                .satisfies(change -> {
                  assertThat(change).containsEntry("reason", "UNIT_INELIGIBLE");
                  assertThat(((Number) change.get("previousAmount")).doubleValue()).isEqualTo(5.0);
                  assertThat(((Number) change.get("amount")).doubleValue()).isEqualTo(0.0);
                });
          });
      assertThat(
              jdbc.queryForObject(
                  "SELECT ended_at IS NOT NULL FROM material_claim WHERE id = ?",
                  Boolean.class,
                  fixture.invalidClaim()))
          .isTrue();
      assertThat(
              jdbc.queryForObject(
                  "SELECT ended_at IS NULL FROM material_claim WHERE id = ?",
                  Boolean.class,
                  fixture.validClaim()))
          .isTrue();
      assertThat(
              jdbc.queryForObject(
                  "SELECT original_profit_org_unit_id FROM job_order WHERE id = ?",
                  UUID.class,
                  fixture.order()))
          .isNull();
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM audit_event WHERE subject_id = ? AND event_type ="
                      + " 'JOB_ORDER_CLAIM_WITHDRAWN' AND details LIKE '%UNIT_INELIGIBLE%'",
                  Integer.class, fixture.order()))
          .isEqualTo(1);
      assertThat(claims.findByJobOrderIdOrderByCreatedAtDesc(fixture.order()))
          .extracting(MaterialClaim::getId)
          .containsExactly(fixture.validClaim());

      startup.run(new DefaultApplicationArguments());

      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM audit_event WHERE subject_id = ? AND event_type ="
                      + " 'JOB_ORDER_CLAIM_WITHDRAWN'",
                  Integer.class,
                  fixture.order()))
          .isEqualTo(1);
      assertThat(claimFacts(fixture.order())).containsExactlyInAnyOrderElementsOf(ended);
    } finally {
      transactions.executeWithoutResult(_ -> {
        jdbc.update("DELETE FROM notification WHERE entity_id = ?", fixture.order());
        jdbc.update("DELETE FROM material_claim WHERE job_order_id = ?", fixture.order());
        orders.deleteById(fixture.order());
        orders.flush();
        units.deleteAllById(fixture.units());
        units.flush();
        materials.deleteById(fixture.material());
      });
    }
  }

  private Fixture seed(boolean inactive) {
    OrgUnit profit = ProfitHierarchyFixtures.persistedProfitArea(units);
    String tag = UUID.randomUUID().toString().substring(0, 8);
    Squadron valid = squadron("Startup valid " + tag, profit);
    Squadron invalid = squadron("Startup invalid " + tag, inactive ? profit : null);
    invalid.setActive(!inactive);
    units.saveAndFlush(invalid);
    jdbc.update("UPDATE org_unit SET is_profit_eligible = false WHERE id = ?", valid.getId());
    jdbc.update("UPDATE org_unit SET is_profit_eligible = true WHERE id = ?", invalid.getId());
    SpecialCommand responsible = new SpecialCommand();
    responsible.setName("Startup responsible " + tag);
    responsible.setShorthand("SR" + tag);
    responsible.setParent(profit);
    responsible = units.saveAndFlush(responsible);
    Material material = new Material();
    material.setName("Startup material " + tag);
    material.setType(MaterialType.RAW);
    material = materials.saveAndFlush(material);
    var quality = tiers.findByCode("GOOD").orElseThrow();
    JobOrder order =
        JobOrder.builder()
            .responsibleOrgUnit(responsible)
            .requestingOrgUnit(responsible)
            .handle("Startup fixture")
            .build();
    order.addMaterial(
        JobOrderMaterial.builder().material(material).qualityTier(quality).amount(10.0).build());
    order = orders.saveAndFlush(order);
    MaterialClaim legal =
        claims.saveAndFlush(
            MaterialClaim.builder()
                .jobOrder(order)
                .material(material)
                .qualityTier(quality)
                .claimingOrgUnit(valid)
                .participation(persistedParticipation(participations, order, valid))
                .amount(3.0)
                .build());
    MaterialClaim illegal =
        claims.saveAndFlush(
            MaterialClaim.builder()
                .jobOrder(order)
                .material(material)
                .qualityTier(quality)
                .claimingOrgUnit(invalid)
                .participation(persistedParticipation(participations, order, invalid))
                .amount(5.0)
                .build());
    return new Fixture(
        order.getId(),
        material.getId(),
        legal.getId(),
        illegal.getId(),
        List.of(valid.getId(), invalid.getId(), responsible.getId()));
  }

  private Squadron squadron(String name, OrgUnit parent) {
    Squadron unit = new Squadron();
    unit.setName(name);
    unit.setShorthand(name);
    unit.setParent(parent);
    return units.saveAndFlush(unit);
  }

  private static @NotNull List<Map<String, Object>> stableFacts(
      @NotNull List<Map<String, Object>> rows) {
    return rows.stream()
        .map(row -> {
          Map<String, Object> stable = new HashMap<>(row);
          stable.remove("amount");
          stable.remove("changes");
          return stable;
        })
        .toList();
  }

  private List<Map<String, Object>> claimFacts(UUID order) {
    return jdbc.queryForList(
        "SELECT id, job_order_id, material_id, quality_tier_id, claiming_org_unit_id, amount,"
            + " claimed_by_user_id, created_at, changes FROM material_claim WHERE job_order_id = ?"
            + " ORDER BY id",
        order);
  }

  /** Synthetic identities owned by this lifecycle replay. */
  private record Fixture(
      UUID order, UUID material, UUID validClaim, UUID invalidClaim, List<UUID> units) {}
}
