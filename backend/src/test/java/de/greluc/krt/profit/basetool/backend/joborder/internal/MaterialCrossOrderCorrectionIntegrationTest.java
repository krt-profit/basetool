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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryRowVersionDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.joborder.api.MaterialContributionHistoryDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.MaterialProvisionEvidence;
import de.greluc.krt.profit.basetool.backend.joborder.api.MaterialProvisionFactChange;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.platform.api.OrgUnitContextualAuthority;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;

/** Verifies current authority and atomic correction across genuinely connected order receipts. */
@SpringBootTest
@WithMockUser(roles = "ADMIN")
@RequiredArgsConstructor(onConstructor_ = @Autowired)
class MaterialCrossOrderCorrectionIntegrationTest extends MaterialContributionTestSupport {
  /** Persists ordinary Logistician capability on the actor's actual SK memberships. */
  private final OrgUnitMembershipRepository memberships;

  @Test
  void correctingAnOriginalReceiptRequiresAuthorityForItsLaterCreditOnAnotherOrder()
      throws Exception {
    Fixture originalOrder = seed();
    Fixture laterOrder = secondOrder(originalOrder);
    assertThat(laterOrder.orderId()).isNotEqualTo(originalOrder.orderId());
    UUID firstResponsible = responsible(originalOrder);
    UUID secondResponsible = responsible(laterOrder);
    assertThat(secondResponsible).isNotEqualTo(firstResponsible);
    var originalClaim =
        materialClaimService.upsertClaim(
            originalOrder.orderId(),
            claim(originalOrder.materialId(), originalOrder.squadronAId(), 3.0));
    var laterClaim =
        materialClaimService.upsertClaim(
            laterOrder.orderId(), claim(laterOrder.materialId(), laterOrder.squadronBId(), 1.0));
    StockFixture stock = stock(originalOrder, 4.0);
    provide(originalOrder, originalClaim, stock, 4.0);
    mvc()
        .perform(
            patch("/api/v1/inventory/{id}/allocation", stock.rowId())
                .with(asAdmin(stock.holderId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.createObjectNode()
                        .put("field", "JOB_ORDER")
                        .put("targetId", originalOrder.orderId().toString())
                        .put("amount", 3.0)
                        .put("version", inventory(originalOrder, stock).path("version").asLong())
                        .toString()))
        .andExpect(status().isOk());
    provide(laterOrder, laterClaim, stock, 1.0);
    var beforeWithdrawal = contributionHistory(originalOrder.orderId(), originalClaim.id());
    mvc()
        .perform(
            delete(
                    "/api/v1/orders/{order}/claims/{claim}",
                    originalOrder.orderId(),
                    originalClaim.id())
                .param("version", beforeWithdrawal.version().toString())
                .with(asAdmin(stock.holderId())))
        .andExpect(status().isNoContent());

    grantLogistician(stock.holderId(), firstResponsible);
    Snapshot before =
        snapshot(originalOrder, laterOrder, originalClaim.id(), laterClaim.id(), stock);
    assertThat(before.original().creditedAmount()).isEqualTo(3.0);
    assertThat(before.original().openAmount()).isZero();
    assertThat(before.original().endedAt()).isNotNull();
    assertThat(before.later().creditedAmount()).isEqualTo(1.0);
    assertThat(before.later().openAmount()).isZero();
    assertThat(before.later().endedAt()).isNull();
    assertThat(before.original().provisions()).hasSize(1);
    assertThat(before.later().provisions()).hasSize(1);
    var originalReceipt = before.original().provisions().getFirst();
    var laterReceipt = before.later().provisions().getFirst();
    assertThat(originalReceipt.evidence()).isEqualTo(MaterialProvisionEvidence.OBSERVED);
    assertThat(originalReceipt.quantityAmount()).isEqualTo(4.0);
    assertThat(originalReceipt.amount()).isEqualTo(3.0);
    assertThat(laterReceipt.id()).isNotEqualTo(originalReceipt.id());
    assertThat(laterReceipt.evidence()).isEqualTo(MaterialProvisionEvidence.OBSERVED);
    assertThat(laterReceipt.quantityAmount()).isEqualTo(1.0);
    assertThat(laterReceipt.amount()).isEqualTo(1.0);
    assertThat(before.stock().path("amount").asDouble()).isEqualTo(4.0);
    assertThat(before.stock().path("jobOrderAllocations").size()).isEqualTo(2);
    assertThat(allocatedTo(before.stock(), originalOrder.orderId())).isEqualTo(3.0);
    assertThat(allocatedTo(before.stock(), laterOrder.orderId())).isEqualTo(1.0);
    assertCorrectionPermission(originalOrder, stock.holderId(), true, firstResponsible);
    assertCorrectionPermission(laterOrder, stock.holderId(), false, firstResponsible);
    mvc()
        .perform(
            get("/api/v1/inventory/material/{id}", originalOrder.materialId())
                .with(asWriter(stock.holderId(), firstResponsible)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].id").value(stock.rowId().toString()))
        .andExpect(jsonPath("$.content[0].canEdit").value(true));

    var correction =
        new MaterialProvisionCorrectionDto(
            MaterialProvisionCorrectionDto.Kind.QUANTITY,
            originalReceipt.version(),
            before.original().version(),
            3.0,
            null,
            null,
            List.of(
                new InventoryRowVersionDto(stock.rowId(), before.stock().path("version").asLong())),
            null,
            null,
            List.of());
    String payload = json.writeValueAsString(correction);
    mvc()
        .perform(
            post(
                    "/api/v1/orders/{order}/claims/provisions/{receipt}/correction",
                    originalOrder.orderId(),
                    originalReceipt.id())
                .with(asWriter(stock.holderId(), firstResponsible))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
        .andExpect(status().isForbidden());
    assertThat(snapshot(originalOrder, laterOrder, originalClaim.id(), laterClaim.id(), stock))
        .isEqualTo(before);

    grantLogistician(stock.holderId(), secondResponsible);
    assertCorrectionPermission(
        laterOrder, stock.holderId(), true, firstResponsible, secondResponsible);
    mvc()
        .perform(
            post(
                    "/api/v1/orders/{order}/claims/provisions/{receipt}/correction",
                    originalOrder.orderId(),
                    originalReceipt.id())
                .with(asWriter(stock.holderId(), firstResponsible, secondResponsible))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
        .andExpect(status().isOk());

    Snapshot after =
        snapshot(originalOrder, laterOrder, originalClaim.id(), laterClaim.id(), stock);
    assertThat(after.original().creditedAmount()).isEqualTo(3.0);
    assertThat(after.original().openAmount()).isZero();
    assertThat(after.original().endedAt()).isEqualTo(before.original().endedAt());
    assertThat(after.original().supporting()).isEqualTo(before.original().supporting());
    assertThat(after.later().creditedAmount()).isZero();
    assertThat(after.later().openAmount()).isEqualTo(1.0);
    assertThat(after.later().endedAt()).isNull();
    assertThat(after.later().supporting()).isTrue();
    assertThat(after.original().provisions().getFirst().quantityAmount()).isEqualTo(3.0);
    assertThat(after.original().provisions().getFirst().amount()).isEqualTo(3.0);
    assertThat(after.later().provisions().getFirst().quantityAmount()).isZero();
    assertThat(after.later().provisions().getFirst().amount()).isZero();
    assertThat(after.original().provisions().getFirst().version())
        .isGreaterThan(originalReceipt.version());
    assertThat(after.later().provisions().getFirst().version())
        .isGreaterThan(laterReceipt.version());
    assertThat(after.later().version()).isGreaterThan(before.later().version());
    assertThat(after.original().provisions().getFirst().factChanges())
        .hasSize(originalReceipt.factChanges().size() + 1)
        .last()
        .satisfies(change -> {
          assertThat(change.reason())
              .isEqualTo(MaterialProvisionFactChange.Reason.FALSE_FACT_CORRECTED);
          assertThat(change.previous()).isNotNull();
          assertThat(change.previous().quantityAmount()).isEqualTo(4.0);
          assertThat(change.current().quantityAmount()).isEqualTo(3.0);
          assertThat(change.current().creditedAmount()).isEqualTo(3.0);
        });
    assertThat(after.later().provisions().getFirst().factChanges())
        .hasSize(laterReceipt.factChanges().size() + 1)
        .last()
        .satisfies(change -> {
          assertThat(change.reason())
              .isEqualTo(MaterialProvisionFactChange.Reason.FALSE_FACT_CORRECTED);
          assertThat(change.previous()).isNotNull();
          assertThat(change.previous().quantityAmount()).isEqualTo(1.0);
          assertThat(change.previous().creditedAmount()).isEqualTo(1.0);
          assertThat(change.current().quantityAmount()).isZero();
          assertThat(change.current().creditedAmount()).isZero();
        });
    assertThat(after.stock().path("id")).isEqualTo(before.stock().path("id"));
    assertThat(after.stock().path("amount").asDouble()).isEqualTo(3.0);
    assertThat(after.stock().path("version").asLong())
        .isGreaterThan(before.stock().path("version").asLong());
    assertThat(after.stock().path("quality")).isEqualTo(before.stock().path("quality"));
    assertThat(after.stock().path("location")).isEqualTo(before.stock().path("location"));
    assertThat(after.stock().path("user")).isEqualTo(before.stock().path("user"));
    assertThat(after.stock().path("owningSquadron"))
        .isEqualTo(before.stock().path("owningSquadron"));
    assertThat(after.stock().path("jobOrderRest").asDouble()).isZero();
    assertThat(
            allocatedTo(after.stock(), originalOrder.orderId())
                + allocatedTo(after.stock(), laterOrder.orderId()))
        .isEqualTo(3.0);
    JsonNode laterMaterial = after.laterOrder().path("materials").get(0);
    assertThat(laterMaterial.path("amount").isNumber()).isTrue();
    assertThat(laterMaterial.path("currentStock").isNumber()).isTrue();
    assertThat(after.later().openAmount())
        .isLessThanOrEqualTo(
            laterMaterial.path("amount").asDouble()
                - laterMaterial.path("currentStock").asDouble());
  }

  /** Creates a distinct valid responsible SK and ten-unit order for the same material. */
  private Fixture secondOrder(Fixture first) {
    return transactionTemplate.execute(_ -> {
      String tag = UUID.randomUUID().toString().substring(0, 8);
      SpecialCommand sk = new SpecialCommand();
      sk.setName("Connected-SK-" + tag);
      sk.setShorthand("C" + tag);
      withinProfit(sk, persistedProfitArea(orgUnitRepository));
      sk = specialCommandRepository.save(sk);
      JobOrder order =
          JobOrder.builder()
              .responsibleOrgUnit(sk)
              .requestingOrgUnit(orgUnitRepository.findById(first.squadronBId()).orElseThrow())
              .handle("connected-order")
              .status(JobOrderStatus.OPEN)
              .build();
      order.addMaterial(
          JobOrderMaterial.builder()
              .material(materialRepository.findById(first.materialId()).orElseThrow())
              .qualityTier(qualityTierRepository.findByCode("GOOD").orElseThrow())
              .amount(10.0)
              .build());
      return new Fixture(
          jobOrderRepository.save(order).getId(),
          first.materialId(),
          first.squadronAId(),
          first.squadronBId());
    });
  }

  /** Resolves the fixture's actual responsible SK without granting any authority. */
  private UUID responsible(Fixture fixture) {
    return transactionTemplate.execute(_ ->
        jobOrderRepository
            .findById(fixture.orderId())
            .orElseThrow()
            .getResponsibleOrgUnit()
            .getId());
  }

  /** Persists one ordinary SK membership carrying its own Logistician capability. */
  private void grantLogistician(UUID actor, UUID sk) {
    transactionTemplate.executeWithoutResult(_ -> {
      User user = users.findById(actor).orElseThrow();
      OrgUnitMembership membership = new OrgUnitMembership();
      membership.setId(new OrgUnitMembershipId(actor, sk));
      membership.setUser(user);
      membership.setKind(orgUnitRepository.findById(sk).orElseThrow().getKind());
      membership.setLogistician(true);
      membership.setJoinedAt(Instant.now());
      memberships.saveAndFlush(membership);
    });
  }

  /** Matches only the persisted ordinary SK grants, with no active-context pin. */
  private RequestPostProcessor asWriter(UUID actor, UUID... skGrants) {
    List<GrantedAuthority> authorities = new ArrayList<>();
    authorities.add(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"));
    authorities.add(new SimpleGrantedAuthority("ROLE_LOGISTICIAN"));
    for (UUID sk : skGrants) {
      authorities.add(new OrgUnitContextualAuthority(Roles.LOGISTICIAN, sk));
    }
    return jwt().jwt(token -> token.subject(actor.toString())).authorities(authorities);
  }

  /** Authenticates fixture writes and the consistent public observer. */
  private RequestPostProcessor asAdmin(UUID actor) {
    return jwt()
        .jwt(token -> token.subject(actor.toString()))
        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }

  /** Checks the sole receipt's current correction permission through public history. */
  private void assertCorrectionPermission(
      Fixture fixture, UUID actor, boolean allowed, UUID... skGrants) throws Exception {
    mvc()
        .perform(
            get("/api/v1/orders/{order}/claims/history", fixture.orderId())
                .with(asWriter(actor, skGrants)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].provisions[0].canCorrect").value(allowed));
  }

  /** Reads complete public order, contribution and stock facts for atomicity comparison. */
  private Snapshot snapshot(
      Fixture first, Fixture second, UUID firstClaim, UUID secondClaim, StockFixture stock)
      throws Exception {
    return new Snapshot(
        read("/api/v1/orders/" + first.orderId(), stock.holderId()),
        read("/api/v1/orders/" + second.orderId(), stock.holderId()),
        contributionHistory(first.orderId(), firstClaim),
        contributionHistory(second.orderId(), secondClaim),
        inventory(first, stock));
  }

  /** Reads the sole actual row through the public material inventory projection. */
  private JsonNode inventory(Fixture fixture, StockFixture stock) throws Exception {
    JsonNode content =
        read("/api/v1/inventory/material/" + fixture.materialId(), stock.holderId())
            .path("content");
    assertThat(content.size()).isEqualTo(1);
    JsonNode row = content.get(0);
    assertThat(row.path("id").asString()).isEqualTo(stock.rowId().toString());
    return row;
  }

  /** Decodes one authenticated public projection without a repository assertion. */
  private JsonNode read(String path, UUID actor) throws Exception {
    String body =
        mvc()
            .perform(get(path).with(asAdmin(actor)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(body);
  }

  /** Reads an order's public earmarked amount, treating a missing slice as zero. */
  private double allocatedTo(JsonNode stock, UUID order) {
    for (JsonNode allocation : stock.path("jobOrderAllocations")) {
      if (allocation.path("jobOrderId").asString().equals(order.toString())) {
        return allocation.path("amount").asDouble();
      }
    }
    return 0.0;
  }

  /** Captures all public fact and version fields affected by one connected correction. */
  private record Snapshot(
      JsonNode originalOrder,
      JsonNode laterOrder,
      MaterialContributionHistoryDto original,
      MaterialContributionHistoryDto later,
      JsonNode stock) {}
}
