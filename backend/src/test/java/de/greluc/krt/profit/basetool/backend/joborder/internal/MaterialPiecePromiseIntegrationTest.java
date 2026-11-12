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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;

/** Verifies whole-piece promises and exact apportionment through public order operations. */
@SpringBootTest
@WithMockUser(roles = "ADMIN")
class MaterialPiecePromiseIntegrationTest extends MaterialContributionTestSupport {
  @Test
  void fractionalPiecePromiseIsRejectedWithoutChangingReadableContributionFacts() throws Exception {
    Fixture fixture = seed();
    transactionTemplate.executeWithoutResult(_ -> {
      var material = materialRepository.findById(fixture.materialId()).orElseThrow();
      material.setQuantityType(QuantityType.PIECE);
      materialRepository.saveAndFlush(material);
    });
    mvc()
        .perform(
            post("/api/v1/orders/{id}/claims", fixture.orderId())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        claim(fixture.materialId(), fixture.squadronAId(), 3.5))))
        .andExpect(status().isBadRequest());
    assertThat(onlyBucket(fixture.orderId()).claims()).isEmpty();
    mvc()
        .perform(
            post("/api/v1/orders/{id}/claims", fixture.orderId())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        claim(fixture.materialId(), fixture.squadronAId(), 4.0))))
        .andExpect(status().isCreated());
    assertThat(onlyBucket(fixture.orderId()).claimedAmount()).isEqualTo(4.0);
  }

  @Test
  void piecePromisesFourAndSixReduceToTwoAndFourWithoutGrowingWhenNeedRises() throws Exception {
    Fixture fixture = seed();
    transactionTemplate.executeWithoutResult(_ -> {
      var material = materialRepository.findById(fixture.materialId()).orElseThrow();
      material.setQuantityType(QuantityType.PIECE);
      materialRepository.saveAndFlush(material);
    });
    var a =
        materialClaimService.upsertClaim(
            fixture.orderId(), claim(fixture.materialId(), fixture.squadronAId(), 4.0));
    var b =
        materialClaimService.upsertClaim(
            fixture.orderId(), claim(fixture.materialId(), fixture.squadronBId(), 6.0));
    var order = jobOrderQueryService.getJobOrderById(fixture.orderId());
    var reduced =
        updateOrder(
            fixture.orderId(),
            new CreateJobOrderDto(
                null,
                null,
                order.handle(),
                null,
                java.util.List.of(new CreateJobOrderMaterialDto(fixture.materialId(), 650, 6.0)),
                order.version()));
    var afterA = contributionHistory(fixture.orderId(), a.id());
    var afterB = contributionHistory(fixture.orderId(), b.id());
    assertThat(afterA.openAmount()).isEqualTo(2.0);
    assertThat(afterB.openAmount()).isEqualTo(4.0);
    assertThat(afterA.creditedAmount()).isZero();
    assertThat(afterB.creditedAmount()).isZero();
    assertThat(onlyBucket(fixture.orderId()).claimedAmount()).isEqualTo(6.0);

    updateOrder(
        fixture.orderId(),
        new CreateJobOrderDto(
            null,
            null,
            reduced.handle(),
            null,
            java.util.List.of(new CreateJobOrderMaterialDto(fixture.materialId(), 650, 10.0)),
            reduced.version()));
    assertThat(contributionHistory(fixture.orderId(), a.id())).isEqualTo(afterA);
    assertThat(contributionHistory(fixture.orderId(), b.id())).isEqualTo(afterB);
  }

  private de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderDto updateOrder(
      java.util.UUID orderId, CreateJobOrderDto request) throws Exception {
    String response =
        mvc()
            .perform(
                put("/api/v1/orders/{id}", orderId)
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readValue(
        response, de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderDto.class);
  }
}
