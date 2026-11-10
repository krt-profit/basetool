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

import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryRowVersionDto;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;

/** Verifies atomic numeric-domain refusals through the public provision-correction API. */
@SpringBootTest
@WithMockUser(roles = "ADMIN")
class MaterialQuantityBoundsIntegrationTest extends MaterialContributionTestSupport {
  @Test
  void unrepresentableQuantityIsRejectedWithoutChangingFactsAndTheObservedVersionCanRetry()
      throws Exception {
    Fixture fixture = seed();
    var claim =
        materialClaimService.upsertClaim(
            fixture.orderId(), claim(fixture.materialId(), fixture.squadronAId(), 4.0));
    StockFixture stock = stock(fixture, 3.0);
    provide(fixture, claim, stock, 3.0);
    var before = contributionHistory(fixture.orderId(), claim.id());
    var receipt = before.provisions().getFirst();
    Long rowVersion = stockReads.findRow(stock.rowId()).orElseThrow().getVersion();
    var invalid =
        new MaterialProvisionCorrectionDto(
            MaterialProvisionCorrectionDto.Kind.QUANTITY,
            receipt.version(),
            before.version(),
            1.0e20,
            null,
            null,
            List.of(new InventoryRowVersionDto(stock.rowId(), rowVersion)),
            stock.rowId());

    mvc()
        .perform(
            post(
                    "/api/v1/orders/{id}/claims/provisions/{credit}/correction",
                    fixture.orderId(),
                    receipt.id())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(invalid)))
        .andExpect(status().isBadRequest());

    assertThat(contributionHistory(fixture.orderId(), claim.id())).isEqualTo(before);
    var unchanged = stockReads.findRow(stock.rowId()).orElseThrow();
    assertThat(unchanged.getAmount()).isEqualTo(3.0);
    assertThat(unchanged.getVersion()).isEqualTo(rowVersion);
    var retry =
        new MaterialProvisionCorrectionDto(
            MaterialProvisionCorrectionDto.Kind.QUANTITY,
            receipt.version(),
            before.version(),
            4.0,
            null,
            null,
            List.of(new InventoryRowVersionDto(stock.rowId(), rowVersion)),
            stock.rowId());
    mvc()
        .perform(
            post(
                    "/api/v1/orders/{id}/claims/provisions/{credit}/correction",
                    fixture.orderId(),
                    receipt.id())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(retry)))
        .andExpect(status().isOk());
    var after = contributionHistory(fixture.orderId(), claim.id());
    assertThat(after.openAmount()).isZero();
    assertThat(after.creditedAmount()).isEqualTo(4.0);
    assertThat(after.provisions().getFirst().quantityAmount()).isEqualTo(4.0);
    assertThat(stockReads.findRow(stock.rowId()).orElseThrow().getAmount()).isEqualTo(4.0);
  }
}
