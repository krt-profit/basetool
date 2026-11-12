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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;

/** Verifies ordinary attribution choices cannot silently become unattributed stock. */
@SpringBootTest
@WithMockUser(roles = "ADMIN")
class MaterialProvisionOpenPromiseIntegrationTest extends MaterialContributionTestSupport {
  @Test
  void aCurrentZeroOpenPromiseCannotSilentlyAcceptNewAttributedStock() throws Exception {
    Fixture fixture = seed();
    var a =
        materialClaimService.upsertClaim(
            fixture.orderId(), claim(fixture.materialId(), fixture.squadronAId(), 4.0));
    var b =
        materialClaimService.upsertClaim(
            fixture.orderId(), claim(fixture.materialId(), fixture.squadronBId(), 6.0));
    StockFixture original = stock(fixture, 4.0);
    provide(fixture, a, original, 4.0);
    var beforeA = contributionHistory(fixture.orderId(), a.id());
    var beforeB = contributionHistory(fixture.orderId(), b.id());
    assertThat(beforeA.openAmount()).isZero();
    assertThat(beforeA.creditedAmount()).isEqualTo(4.0);
    StockFixture fresh = stock(fixture, 1.0);
    Long rowVersion = stockReads.findRow(fresh.rowId()).orElseThrow().getVersion();
    var ordinary =
        Map.of(
            "field",
            "JOB_ORDER",
            "targetId",
            fixture.orderId(),
            "amount",
            1.0,
            "version",
            rowVersion);
    var attributed = new java.util.HashMap<String, Object>(ordinary);
    attributed.put("provision", Map.of("claimId", a.id(), "claimVersion", beforeA.version()));

    mvc()
        .perform(
            post("/api/v1/inventory/{id}/allocation", fresh.rowId())
                .with(
                    jwt()
                        .jwt(token -> token.subject(fresh.holderId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(attributed)))
        .andExpect(status().isBadRequest());
    assertThat(contributionHistory(fixture.orderId(), a.id())).isEqualTo(beforeA);
    assertThat(contributionHistory(fixture.orderId(), b.id())).isEqualTo(beforeB);
    transactionTemplate.executeWithoutResult(_ -> {
      var unchanged = stockReads.findRow(fresh.rowId()).orElseThrow();
      assertThat(unchanged.getAmount()).isEqualTo(1.0);
      assertThat(unchanged.getVersion()).isEqualTo(rowVersion);
      assertThat(unchanged.isEarmarkedForJobOrder(fixture.orderId())).isFalse();
    });

    mvc()
        .perform(
            post("/api/v1/inventory/{id}/allocation", fresh.rowId())
                .with(
                    jwt()
                        .jwt(token -> token.subject(fresh.holderId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(ordinary)))
        .andExpect(status().isOk());
    assertThat(contributionHistory(fixture.orderId(), a.id())).isEqualTo(beforeA);
    assertThat(contributionHistory(fixture.orderId(), b.id()).openAmount()).isEqualTo(5.0);
    assertThat(contributionHistory(fixture.orderId(), b.id()).creditedAmount()).isZero();
  }
}
