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
 * Integration test for the {@code materialClaims} export section: a claim names its bucket by the
 * code of the claim's quality tier (REQ-ORDERS-036, REQ-DATA-021).
 */
@SpringBootTest
@Transactional
class DataExportMaterialClaimSectionIntegrationTest {

  @Autowired private OrgUnitRepository orgUnitRepository;

  private static final UUID GOOD_TIER = UUID.fromString("6b1f2e0a-3c1d-4f5e-9a10-000000000650");

  @Autowired private DataExportService dataExportService;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbc;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void aClaimIncludesItsQualityAndOwnEndingHistory(boolean ended) {
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
    OffsetDateTime recordedAt = OffsetDateTime.parse("2026-05-05T12:00:00Z");
    jdbc.update(
        "INSERT INTO material_claim (id, job_order_id, material_id, quality_tier_id,"
            + " claiming_org_unit_id, amount, claimed_by_user_id, created_at, ended_at)"
            + " VALUES (?, ?, ?, ?, ?, 12, ?, ?, ?)",
        UUID.randomUUID(),
        order,
        material,
        GOOD_TIER,
        unit,
        subject,
        recordedAt,
        ended ? recordedAt : null);

    List<Map<String, Object>> rows = sectionRows(subject);

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0))
        .containsEntry("material", "Zzz Claim Ore")
        .containsEntry("quality_requirement", "GOOD")
        .containsEntry("ended_at", ended ? rows.getFirst().get("created_at") : null);
    assertThat(rows.getFirst().get("created_at")).isNotNull();
  }

  private @NotNull List<Map<String, Object>> sectionRows(@NotNull UUID subject) {
    return dataExportService.export(subject).sections().stream()
        .filter(s -> s.key().equals("materialClaims"))
        .findFirst()
        .orElseThrow()
        .rows();
  }
}
