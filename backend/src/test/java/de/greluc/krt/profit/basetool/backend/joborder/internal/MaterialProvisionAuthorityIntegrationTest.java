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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.joborder.api.ClaimDto;
import de.greluc.krt.profit.basetool.backend.kernel.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.OrgUnitContextualAuthority;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Verifies correction authority through public authenticated order and Inventory operations. */
@SpringBootTest
@WithMockUser(roles = "ADMIN")
class MaterialProvisionAuthorityIntegrationTest extends MaterialContributionTestSupport {
  @Autowired private OrgUnitMembershipRepository memberships;

  @Test
  void onlyTargetStaffelWriterCannotRelabelAnotherStaffelsHistoricalCredit() throws Exception {
    Fixture fixture = seed();
    ClaimDto original =
        materialClaimService.upsertClaim(
            fixture.orderId(), claim(fixture.materialId(), fixture.squadronAId(), 4.0));
    ClaimDto target =
        materialClaimService.upsertClaim(
            fixture.orderId(), claim(fixture.materialId(), fixture.squadronBId(), 6.0));
    StockFixture stock = stock(fixture, 3.0);
    provide(fixture, original, stock, 3.0);
    var before = contributionHistory(fixture.orderId(), original.id());
    var receipt = before.provisions().getFirst();
    UUID actor = writer(fixture.squadronBId());
    var correction =
        new MaterialProvisionCorrectionDto(
            MaterialProvisionCorrectionDto.Kind.ATTRIBUTION,
            receipt.version(),
            before.version(),
            3.0,
            target.id(),
            target.version(),
            List.of(),
            null);

    mvc()
        .perform(
            post(
                    "/api/v1/orders/{order}/claims/provisions/{receipt}/correction",
                    fixture.orderId(),
                    receipt.id())
                .with(asWriter(actor, fixture.squadronBId()))
                .header("X-Active-Org-Unit-Id", fixture.squadronBId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(correction)))
        .andExpect(status().isForbidden());

    assertThat(contributionHistory(fixture.orderId(), original.id())).isEqualTo(before);
    assertThat(contributionHistory(fixture.orderId(), target.id()).creditedAmount()).isZero();
    assertThat(stockReads.findRow(stock.rowId()).orElseThrow().getAmount()).isEqualTo(3.0);
  }

  @Test
  void privateRelocatedStockIsHiddenWhileOwnAttributionRemainsCorrectable() throws Exception {
    Fixture fixture = seed();
    ClaimDto original =
        materialClaimService.upsertClaim(
            fixture.orderId(), claim(fixture.materialId(), fixture.squadronAId(), 4.0));
    materialClaimService.upsertClaim(
        fixture.orderId(), claim(fixture.materialId(), fixture.squadronBId(), 6.0));
    StockFixture stock = stock(fixture, 3.0);
    provide(fixture, original, stock, 3.0);
    mvc()
        .perform(
            delete("/api/v1/inventory/{row}/allocation", stock.rowId())
                .with(
                    jwt()
                        .jwt(token -> token.subject(stock.holderId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.createObjectNode()
                        .put("field", "JOB_ORDER")
                        .put("targetId", fixture.orderId().toString())
                        .put(
                            "version", stockReads.findRow(stock.rowId()).orElseThrow().getVersion())
                        .toString()))
        .andExpect(status().isOk());
    String privateRow =
        mvc()
            .perform(
                post("/api/v1/inventory/{row}/personal-rebook", stock.rowId())
                    .with(
                        jwt()
                            .jwt(token -> token.subject(stock.holderId().toString()))
                            .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"amount\":3,\"version\":"
                            + stockReads.findRow(stock.rowId()).orElseThrow().getVersion()
                            + "}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID privateId = UUID.fromString(json.readTree(privateRow).get("id").asString());
    var before = contributionHistory(fixture.orderId(), original.id());
    var receipt = before.provisions().getFirst();
    UUID actor = writer(fixture.squadronAId());

    var denied =
        mvc()
            .perform(
                get(
                        "/api/v1/orders/{order}/claims/provisions/{receipt}/correction-options",
                        fixture.orderId(),
                        receipt.id())
                    .with(asWriter(actor, fixture.squadronAId()))
                    .header("X-Active-Org-Unit-Id", fixture.squadronAId()))
            .andExpect(status().isForbidden())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(denied).doesNotContain(privateId.toString(), "holderEffectiveName", "version");
    var forbiddenQuantity =
        new MaterialProvisionCorrectionDto(
            MaterialProvisionCorrectionDto.Kind.QUANTITY,
            receipt.version(),
            before.version(),
            2.0,
            null,
            null,
            List.of(),
            null);
    String deniedWrite =
        mvc()
            .perform(
                post(
                        "/api/v1/orders/{order}/claims/provisions/{receipt}/correction",
                        fixture.orderId(),
                        receipt.id())
                    .with(asWriter(actor, fixture.squadronAId()))
                    .header("X-Active-Org-Unit-Id", fixture.squadronAId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(forbiddenQuantity)))
            .andExpect(status().isForbidden())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(deniedWrite).doesNotContain(privateId.toString(), "holderEffectiveName", "version");
    assertThat(contributionHistory(fixture.orderId(), original.id())).isEqualTo(before);
    var correction =
        new MaterialProvisionCorrectionDto(
            MaterialProvisionCorrectionDto.Kind.ATTRIBUTION,
            receipt.version(),
            before.version(),
            0.0,
            null,
            null,
            List.of(),
            null);
    mvc()
        .perform(
            post(
                    "/api/v1/orders/{order}/claims/provisions/{receipt}/correction",
                    fixture.orderId(),
                    receipt.id())
                .with(asWriter(actor, fixture.squadronAId()))
                .header("X-Active-Org-Unit-Id", fixture.squadronAId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(correction)))
        .andExpect(status().isOk());

    assertThat(contributionHistory(fixture.orderId(), original.id()).creditedAmount()).isZero();
    assertThat(stockReads.findRow(privateId).orElseThrow().getAmount()).isEqualTo(3.0);
  }

  @Test
  void retainedOutsideProfitReadDoesNotOfferAnUnavailableLegacyCorrection() throws Exception {
    Fixture fixture = seed();
    ClaimDto original =
        materialClaimService.upsertClaim(
            fixture.orderId(), claim(fixture.materialId(), fixture.squadronAId(), 4.0));
    StockFixture stock = stock(fixture, 3.0);
    provide(fixture, original, stock, 3.0);
    UUID actor = writer(fixture.squadronAId());
    Long unitVersion = orgUnitRepository.findById(fixture.squadronAId()).orElseThrow().getVersion();
    mvc()
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                    "/api/v1/org-units/{unit}/parent", fixture.squadronAId())
                .with(
                    jwt()
                        .jwt(token -> token.subject(stock.holderId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"parentOrgUnitId\":null,\"version\":" + unitVersion + "}"))
        .andExpect(status().isOk());
    var retained = contributionHistory(fixture.orderId(), original.id());
    var receipt = retained.provisions().getFirst();

    mvc()
        .perform(
            get("/api/v1/orders/{order}/claims/history", fixture.orderId())
                .with(asWriter(actor, fixture.squadronAId()))
                .header("X-Active-Org-Unit-Id", fixture.squadronAId()))
        .andExpect(status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$[0].provisions[0].canCorrect")
                .value(false));
    mvc()
        .perform(
            get(
                    "/api/v1/orders/{order}/claims/provisions/{receipt}/attribution-options",
                    fixture.orderId(),
                    receipt.id())
                .with(asWriter(actor, fixture.squadronAId()))
                .header("X-Active-Org-Unit-Id", fixture.squadronAId()))
        .andExpect(status().isForbidden());
    var correction =
        new MaterialProvisionCorrectionDto(
            MaterialProvisionCorrectionDto.Kind.ATTRIBUTION,
            receipt.version(),
            retained.version(),
            0.0,
            null,
            null,
            List.of(),
            null);
    mvc()
        .perform(
            post(
                    "/api/v1/orders/{order}/claims/provisions/{receipt}/correction",
                    fixture.orderId(),
                    receipt.id())
                .with(asWriter(actor, fixture.squadronAId()))
                .header("X-Active-Org-Unit-Id", fixture.squadronAId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(correction)))
        .andExpect(status().isForbidden());
    assertThat(contributionHistory(fixture.orderId(), original.id())).isEqualTo(retained);
  }

  private UUID writer(UUID unitId) {
    return transactionTemplate.execute(_ -> {
      User user = new User();
      user.setId(UUID.randomUUID());
      user.setUsername("correction-writer-" + user.getId());
      user.setDisplayName("Correction writer");
      user = users.saveAndFlush(user);
      OrgUnitMembership membership = new OrgUnitMembership();
      membership.setId(new OrgUnitMembershipId(user.getId(), unitId));
      membership.setUser(user);
      membership.setKind(OrgUnitKind.SQUADRON);
      membership.setJoinedAt(Instant.now());
      memberships.saveAndFlush(membership);
      return user.getId();
    });
  }

  private RequestPostProcessor asWriter(UUID actor, UUID unit) {
    return jwt()
        .jwt(token -> token.subject(actor.toString()))
        .authorities(
            new SimpleGrantedAuthority("ROLE_KRT_MEMBER"),
            new SimpleGrantedAuthority("ROLE_LOGISTICIAN"),
            new OrgUnitContextualAuthority(Roles.LOGISTICIAN, unit));
  }
}
