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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;

/** Verifies complete physical handovers and final outstanding demand through public operations. */
@SpringBootTest
@WithMockUser(roles = "ADMIN")
class MaterialHandoverCapacityIntegrationTest extends MaterialContributionTestSupport {
  @Test
  void twoLotHandoverKeepsCreditOnceAndBothPromisesAtTheFinalDemand() throws Exception {
    Fixture fixture = seed();
    var a =
        materialClaimService.upsertClaim(
            fixture.orderId(), claim(fixture.materialId(), fixture.squadronAId(), 4.0));
    var b =
        materialClaimService.upsertClaim(
            fixture.orderId(), claim(fixture.materialId(), fixture.squadronBId(), 6.0));
    StockFixture first = stock(fixture, 2.0);
    provide(fixture, a, first, 2.0);
    var currentA =
        onlyBucket(fixture.orderId()).claims().stream()
            .filter(row -> row.id().equals(a.id()))
            .findFirst()
            .orElseThrow();
    StockFixture second = stock(fixture, 1.0);
    provide(fixture, currentA, second, 1.0);
    var before = contributionHistory(fixture.orderId(), a.id());
    assertThat(before.openAmount()).isEqualTo(1.0);
    assertThat(before.creditedAmount()).isEqualTo(3.0);
    var request =
        new JobOrderHandoverCreateDto(
            Instant.now(),
            "Handover recipient",
            null,
            List.of(
                new JobOrderHandoverItemCreateDto(first.rowId(), 2.0, null, null),
                new JobOrderHandoverItemCreateDto(second.rowId(), 1.0, null, null)));

    mvc()
        .perform(
            post("/api/v1/orders/{order}/handovers", fixture.orderId())
                .with(
                    jwt()
                        .jwt(token -> token.subject(first.holderId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request)))
        .andExpect(result ->
            assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(201));

    var bucket = onlyBucket(fixture.orderId());
    assertThat(bucket.requiredAmount()).isEqualTo(7.0);
    assertThat(bucket.claimedAmount()).isEqualTo(7.0);
    assertThat(contributionHistory(fixture.orderId(), a.id()).openAmount()).isEqualTo(1.0);
    assertThat(contributionHistory(fixture.orderId(), a.id()).creditedAmount()).isEqualTo(3.0);
    assertThat(contributionHistory(fixture.orderId(), b.id()).openAmount()).isEqualTo(6.0);
    assertThat(contributionHistory(fixture.orderId(), b.id()).creditedAmount()).isZero();
    assertThat(stockReads.findRow(first.rowId())).isEmpty();
    assertThat(stockReads.findRow(second.rowId())).isEmpty();
  }

  @Test
  void fulfillingHandoverReleasesOtherLotsAndKeepsEligibleSupportAndPhysicalRemainder()
      throws Exception {
    Fixture fixture = seed();
    var a =
        materialClaimService.upsertClaim(
            fixture.orderId(), claim(fixture.materialId(), fixture.squadronAId(), 4.0));
    var b =
        materialClaimService.upsertClaim(
            fixture.orderId(), claim(fixture.materialId(), fixture.squadronBId(), 6.0));
    StockFixture selected = stock(fixture, 10.0);
    StockFixture spare = stock(fixture, 1.0);
    for (StockFixture row : List.of(selected, spare)) {
      double amount = row.equals(selected) ? 10.0 : 1.0;
      mvc()
          .perform(
              post("/api/v1/inventory/{id}/allocation", row.rowId())
                  .with(
                      jwt()
                          .jwt(token -> token.subject(row.holderId().toString()))
                          .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      json.writeValueAsString(
                          java.util.Map.of(
                              "field",
                              "JOB_ORDER",
                              "targetId",
                              fixture.orderId(),
                              "amount",
                              amount,
                              "version",
                              stockReads.findRow(row.rowId()).orElseThrow().getVersion()))))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }
    var request =
        new JobOrderHandoverCreateDto(
            Instant.now(),
            "Handover recipient",
            null,
            List.of(new JobOrderHandoverItemCreateDto(selected.rowId(), 10.0, null, null)));
    mvc()
        .perform(
            post("/api/v1/orders/{order}/handovers", fixture.orderId())
                .with(
                    jwt()
                        .jwt(token -> token.subject(selected.holderId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request)))
        .andExpect(result ->
            assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(201));
    assertThat(stockReads.findRow(selected.rowId())).isEmpty();
    transactionTemplate.executeWithoutResult(_ -> {
      var remaining = stockReads.findRow(spare.rowId()).orElseThrow();
      assertThat(remaining.getAmount()).isEqualTo(1.0);
      assertThat(remaining.isEarmarkedForJobOrder(fixture.orderId())).isFalse();
    });
    for (var claim : List.of(a, b)) {
      var history = contributionHistory(fixture.orderId(), claim.id());
      assertThat(history.openAmount()).isZero();
      assertThat(history.creditedAmount()).isZero();
      assertThat(history.endedAt()).isNotNull();
      assertThat(history.supporting()).isTrue();
      assertThat(history.provisions()).isEmpty();
    }
    mvc()
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/v1/orders/{id}", fixture.orderId())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status")
                .value("COMPLETED"));
  }
}
