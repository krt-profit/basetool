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

package de.greluc.krt.profit.basetool.backend.service;

import static de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyFixtures.persistedProfitArea;
import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.privacy.api.DataExportService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies personal claim exports disclose only directly associated contribution facts.
 *
 * <p>Current and historical quality facts remain readable (REQ-SEC-058, REQ-DATA-021).
 */
@SpringBootTest
@Transactional
class DataExportMaterialClaimSectionIntegrationTest {

  @Autowired private OrgUnitRepository orgUnitRepository;

  private static final UUID GOOD_TIER = UUID.fromString("6b1f2e0a-3c1d-4f5e-9a10-000000000650");

  @Autowired private DataExportService dataExportService;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private tools.jackson.databind.ObjectMapper json;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void aClaimIncludesItsQualityAndDirectlyLinkedContributionFacts(boolean ended) throws Exception {
    User member = new User();
    member.setId(UUID.randomUUID());
    member.setUsername("ZzzClaimExportZzz");
    UUID subject = userRepository.saveAndFlush(member).getId();
    UUID unit = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO org_unit (id, kind, name, shorthand, active,"
            + " parent_org_unit_id) VALUES (?, 'SQUADRON', 'Zzz Claim Staffel', ?, TRUE, ?)",
        unit,
        "C" + unit.toString().substring(0, 6),
        persistedProfitArea(orgUnitRepository).getId());
    UUID material = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO material (id, name, type, quantity_type, is_manual_raw_material,"
            + " is_job_order, is_visible, source_systems)"
            + " VALUES (?, 'Zzz Claim Ore', 'REFINED', 'SCU', false, false, true, 'UEX_ONLY')",
        material);
    UUID order = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO job_order (id, priority, created_at, updated_at, version, status,"
            + " requesting_org_unit_id, responsible_org_unit_id, type)"
            + " VALUES (?, 2, now(), now(), 0, 'OPEN', ?, ?, 'MATERIAL')",
        order,
        unit,
        unit);
    UUID participation = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO job_order_participation (id, job_order_id, claiming_org_unit_id, active)"
            + " VALUES (?, ?, ?, false)",
        participation,
        order,
        unit);
    UUID claim = UUID.randomUUID();
    OffsetDateTime recordedAt = OffsetDateTime.parse("2026-05-05T12:00:00Z");
    jdbc.update(
        "INSERT INTO material_claim (id, job_order_id, material_id, quality_tier_id,"
            + " claiming_org_unit_id, amount, claimed_by_user_id, created_at, ended_at,"
            + " participation_id, credited_amount, changes) VALUES (?, ?, ?, ?, ?, 12, ?, ?, ?, ?,"
            + " 2, CAST(? AS jsonb))",
        claim,
        order,
        material,
        GOOD_TIER,
        unit,
        subject,
        recordedAt,
        ended ? recordedAt : null,
        participation,
        "[{\"reason\":\"PROVISION_CREDITED\",\"previousAmount\":14,\"amount\":12,"
            + "\"creditDelta\":2,\"recordedAt\":\"2026-05-05T12:00:00Z\",\"creditId\":null}]");
    UUID observed = UUID.randomUUID();
    UUID subset = UUID.randomUUID();
    provision(observed, order, claim, participation, unit, material, "OBSERVED", 2.0, 3.0, 780);
    provision(
        subset, order, claim, participation, unit, material, "CREDITED_SUBSET", 0.0, 1.0, 650);
    provision(
        UUID.randomUUID(),
        order,
        UUID.randomUUID(),
        participation,
        unit,
        material,
        "OBSERVED",
        99.0,
        99.0,
        900);
    provision(
        UUID.randomUUID(),
        UUID.randomUUID(),
        claim,
        participation,
        unit,
        material,
        "OBSERVED",
        88.0,
        88.0,
        900);

    List<Map<String, Object>> rows = sectionRows(subject);

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0))
        .containsEntry("material", "Zzz Claim Ore")
        .containsEntry("quality_requirement", "GOOD")
        .containsEntry("ended_at", ended ? rows.getFirst().get("created_at") : null);
    assertThat(rows.getFirst().get("created_at")).isNotNull();
    assertThat(rows.getFirst()).containsEntry("credited_amount", 2.0);
    var changes = json.readTree(rows.getFirst().get("changes").toString());
    assertThat(changes.get(0).path("reason").asString()).isEqualTo("PROVISION_CREDITED");
    assertThat(changes.get(0).path("previousAmount").asDouble()).isEqualTo(14.0);
    assertThat(changes.get(0).path("amount").asDouble()).isEqualTo(12.0);
    var provisions = json.readTree(rows.getFirst().get("provisions").toString());
    assertThat(provisions.size()).isEqualTo(2);
    for (var receipt : provisions) {
      assertThat(receipt.propertyNames())
          .containsExactlyInAnyOrder(
              "id", "creditedAmount", "evidence", "quantityAmount", "suppliedQuality", "createdAt");
      if (receipt.path("id").asString().equals(observed.toString())) {
        assertThat(receipt.path("creditedAmount").asDouble()).isEqualTo(2.0);
        assertThat(receipt.path("quantityAmount").asDouble()).isEqualTo(3.0);
        assertThat(receipt.path("suppliedQuality").asInt()).isEqualTo(780);
      } else {
        assertThat(receipt.path("id").asString()).isEqualTo(subset.toString());
        assertThat(receipt.path("quantityAmount").isNull()).isTrue();
        assertThat(receipt.path("suppliedQuality").isNull()).isTrue();
      }
    }
    assertThat(rows.getFirst().keySet())
        .containsExactlyInAnyOrder(
            "job_order",
            "material",
            "amount",
            "quality_requirement",
            "created_at",
            "ended_at",
            "credited_amount",
            "changes",
            "provisions");

    jdbc.update("DELETE FROM material_claim WHERE id = ?", claim);
    assertThat(sectionRows(subject)).isEmpty();
  }

  private void provision(
      UUID id,
      UUID order,
      UUID claim,
      UUID participation,
      UUID unit,
      UUID material,
      String evidence,
      double amount,
      double quantity,
      int quality) {
    jdbc.update(
        "INSERT INTO material_provision_credit (id, job_order_id, claim_id, participation_id,"
            + " claiming_org_unit_id, material_id, quality_tier_id, source_row_id, amount,"
            + " quantity_amount, supplied_quality, evidence) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
            + " ?, ?)",
        id,
        order,
        claim,
        participation,
        unit,
        material,
        GOOD_TIER,
        UUID.randomUUID(),
        amount,
        quantity,
        quality,
        evidence);
  }

  private @NotNull List<Map<String, Object>> sectionRows(@NotNull UUID subject) {
    return dataExportService.export(subject).sections().stream()
        .filter(s -> s.key().equals("materialClaims"))
        .findFirst()
        .orElseThrow()
        .rows();
  }
}
