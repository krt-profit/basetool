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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import static de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyFixtures.persistedProfitArea;
import static de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyFixtures.withinProfit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTierRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.ClaimDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.MaterialContributionHistoryDto;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/** Exercises versioned material-promise endings through the public HTTP and domain APIs. */
@SpringBootTest
@WithMockUser(roles = {"ADMIN"})
class MaterialClaimServiceTest {
  @Autowired private WebApplicationContext context;
  @Autowired private TransactionTemplate transactions;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OrgUnitRepository units;
  @Autowired private MaterialRepository materials;
  @Autowired private QualityTierRepository tiers;
  @Autowired private JobOrderRepository orders;
  @Autowired private MaterialClaimService claims;
  @Autowired private JobOrderQueryService query;
  @Autowired private tools.jackson.databind.ObjectMapper json;
  private MockMvc mvc;
  private Fixture fixture;

  private record Fixture(UUID order, UUID material, UUID unit, UUID responsible) {}

  @BeforeEach
  void prepare() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    fixture =
        transactions.execute(_ -> {
          String tag = UUID.randomUUID().toString();
          SpecialCommand responsible = new SpecialCommand();
          responsible.setName("Lifecycle SK " + tag);
          responsible.setShorthand("L" + tag);
          withinProfit(responsible, persistedProfitArea(units));
          responsible = units.saveAndFlush(responsible);
          Squadron unit = new Squadron();
          unit.setName("Lifecycle Staffel " + tag);
          unit.setShorthand("S" + tag);
          withinProfit(unit, persistedProfitArea(units));
          unit = units.saveAndFlush(unit);
          Material material = new Material();
          material.setName("Lifecycle material " + tag);
          material.setType(MaterialType.RAW);
          material = materials.saveAndFlush(material);
          JobOrder order =
              JobOrder.builder()
                  .responsibleOrgUnit(responsible)
                  .requestingOrgUnit(unit)
                  .handle("Lifecycle fixture")
                  .build();
          order.addMaterial(
              JobOrderMaterial.builder()
                  .material(material)
                  .qualityTier(tiers.findByCode("GOOD").orElseThrow())
                  .amount(10.0)
                  .build());
          order = orders.saveAndFlush(order);
          return new Fixture(order.getId(), material.getId(), unit.getId(), responsible.getId());
        });
  }

  @AfterEach
  void removeOwnFixture() {
    if (fixture == null) {
      return;
    }
    transactions.executeWithoutResult(_ -> {
      jdbc.update("DELETE FROM material_claim WHERE job_order_id = ?", fixture.order());
      jdbc.update("DELETE FROM job_order_participation WHERE job_order_id = ?", fixture.order());
      orders.deleteById(fixture.order());
      orders.flush();
      materials.deleteById(fixture.material());
      units.deleteById(fixture.unit());
      units.deleteById(fixture.responsible());
    });
  }

  @Test
  void withdrawalWithoutObservedVersionConflicts() throws Exception {
    ClaimDto observed = promise(4.0, null);

    mvc.perform(
            delete("/api/v1/orders/{order}/claims/{claim}", fixture.order(), observed.id())
                .with(user("lifecycle-admin").roles("ADMIN")))
        .andExpect(status().isConflict());
    assertThat(history(observed.id()).openAmount()).isEqualTo(4.0);
  }

  @Test
  void withdrawingOnePromiseRetainsHistoryAndSupportUnderItsOwnVersion() throws Exception {
    ClaimDto observed = promise(4.0, null);
    Long orderVersion = query.getJobOrderById(fixture.order()).version();

    mvc.perform(
            delete("/api/v1/orders/{order}/claims/{claim}", fixture.order(), observed.id())
                .param("version", observed.version().toString())
                .with(user("lifecycle-admin").roles("ADMIN")))
        .andExpect(status().isNoContent());

    var ended = history(observed.id());
    assertThat(ended.openAmount()).isZero();
    assertThat(ended.endedAt()).isNotNull();
    assertThat(ended.supporting()).isTrue();
    assertThat(ended.changes()).hasSize(2);
    var ending = ended.changes().getLast();
    assertThat(ending.reason().name()).isEqualTo("PROMISE_WITHDRAWN");
    assertThat(ending.previousAmount()).isEqualTo(4.0);
    assertThat(ending.amount()).isZero();
    assertThat(ending.creditDelta()).isZero();
    assertThat(ended.version()).isGreaterThan(observed.version());
    assertThat(query.getJobOrderById(fixture.order()).version()).isEqualTo(orderVersion);

    mvc.perform(
            delete("/api/v1/orders/{order}/claims/{claim}", fixture.order(), observed.id())
                .param("version", observed.version().toString())
                .with(user("lifecycle-admin").roles("ADMIN")))
        .andExpect(status().isConflict());
    mvc.perform(
            delete("/api/v1/orders/{order}/claims/{claim}", fixture.order(), observed.id())
                .param("version", ended.version().toString())
                .with(user("lifecycle-admin").roles("ADMIN")))
        .andExpect(status().isNoContent());
    assertThat(history(observed.id())).isEqualTo(ended);
  }

  private MaterialContributionHistoryDto history(UUID claimId) throws Exception {
    String body =
        mvc.perform(
                get("/api/v1/orders/{order}/claims/history", fixture.order())
                    .with(user("lifecycle-admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    java.util.List<MaterialContributionHistoryDto> records =
        json.readValue(body, new tools.jackson.core.type.TypeReference<>() {});
    return records.stream().filter(row -> row.id().equals(claimId)).findFirst().orElseThrow();
  }

  private ClaimDto promise(double amount, Long version) {
    return claims.upsertClaim(
        fixture.order(),
        new CreateClaimDto(fixture.material(), "GOOD", fixture.unit(), amount, version));
  }
}
