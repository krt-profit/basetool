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

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTierRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.MaterialClaim;
import de.greluc.krt.profit.basetool.backend.joborder.internal.MaterialClaimRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyFixtures;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Exercises the public startup lifecycle against unchanged synthetic legacy promises. */
@SpringBootTest
class ProfitHierarchyStartupIntegrationTest {

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

      assertThat(claimFacts(fixture.order())).containsExactlyInAnyOrderElementsOf(facts);
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
                      + " 'JOB_ORDER_CLAIM_WITHDRAWN' AND details LIKE '%INELIGIBLE_UNIT%'",
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
      assertThat(claimFacts(fixture.order())).containsExactlyInAnyOrderElementsOf(facts);
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
                .amount(3.0)
                .build());
    MaterialClaim illegal =
        claims.saveAndFlush(
            MaterialClaim.builder()
                .jobOrder(order)
                .material(material)
                .qualityTier(quality)
                .claimingOrgUnit(invalid)
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

  private List<Map<String, Object>> claimFacts(UUID order) {
    return jdbc.queryForList(
        "SELECT id, job_order_id, material_id, quality_tier_id, claiming_org_unit_id, amount,"
            + " claimed_by_user_id, created_at FROM material_claim WHERE job_order_id = ? ORDER BY"
            + " id",
        order);
  }

  /** Synthetic identities owned by this lifecycle replay. */
  private record Fixture(
      UUID order, UUID material, UUID validClaim, UUID invalidClaim, List<UUID> units) {}
}
