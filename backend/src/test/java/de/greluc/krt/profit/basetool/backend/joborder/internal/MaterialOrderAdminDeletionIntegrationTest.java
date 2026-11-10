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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.joborder.api.ClaimDto;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;

/**
 * Checks retained physical stock and once-only contribution evidence after public Admin deletion.
 */
@SpringBootTest
@WithMockUser(roles = "ADMIN")
class MaterialOrderAdminDeletionIntegrationTest extends MaterialContributionTestSupport {

  @Test
  void deletingStockBearingOrderReleasesVersionedStockWithoutCreditingItAgain() throws Exception {
    Fixture original = seed();
    Fixture next = sameMaterialAndResponsible(original, seed());
    StockFixture source = stock(original, 3.0);
    StockFixture unrelated = stock(original, 2.0);
    StockSnapshot untouchedBefore = readStock(unrelated, original.orderId());
    ClaimDto first = createPromise(original, source, 4.0);
    ClaimDto later = createPromise(next, source, 3.0);
    provide(original, first, source, 3.0);
    assertThat(contributionHistory(original.orderId(), first.id()).creditedAmount()).isEqualTo(3.0);
    StockSnapshot before = readStock(source, original.orderId());
    assertThat(before.amount()).isEqualTo(3.0);
    assertThat(before.allocated()).isEqualTo(3.0);

    mvc()
        .perform(
            delete("/api/v1/orders/{id}", original.orderId())
                .with(
                    jwt()
                        .jwt(token -> token.subject(source.holderId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isNoContent());

    StockSnapshot after = readStock(source, original.orderId());
    assertThat(after.amount()).isEqualTo(3.0);
    assertThat(after.allocated()).isZero();
    assertThat(after.version())
        .as("An exposed allocation change advances the physical row version")
        .isGreaterThan(before.version());
    assertThat(readStock(unrelated, original.orderId())).isEqualTo(untouchedBefore);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE subject_id = ? AND event_type ="
                    + " 'INVENTORY_ALLOCATION_REMOVED'",
                Long.class,
                source.rowId()))
        .isEqualTo(1L);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE subject_id = ? AND event_type ="
                    + " 'JOB_ORDER_DELETED'",
                Long.class,
                original.orderId()))
        .isEqualTo(1L);
    provide(next, later, source, 3.0);
    var nextHistory = contributionHistory(next.orderId(), later.id());
    assertThat(nextHistory.creditedAmount()).isZero();
    assertThat(nextHistory.openAmount()).isEqualTo(3.0);
    StockSnapshot relinked = readStock(source, next.orderId());
    assertThat(relinked.amount()).isEqualTo(3.0);
    assertThat(relinked.allocated()).isEqualTo(3.0);
  }

  private Fixture sameMaterialAndResponsible(Fixture original, Fixture target) {
    transactionTemplate.executeWithoutResult(_ -> {
      var source = jobOrderRepository.findById(original.orderId()).orElseThrow();
      var next = jobOrderRepository.findById(target.orderId()).orElseThrow();
      next.setResponsibleOrgUnit(source.getResponsibleOrgUnit());
      next.getMaterials()
          .forEach(
              line -> line.setMaterial(materialRepository.getReferenceById(original.materialId())));
      jobOrderRepository.saveAndFlush(next);
    });
    return new Fixture(
        target.orderId(), original.materialId(), target.squadronAId(), target.squadronBId());
  }

  private ClaimDto createPromise(Fixture fixture, StockFixture source, double amount)
      throws Exception {
    String body =
        mvc()
            .perform(
                post("/api/v1/orders/{id}/claims", fixture.orderId())
                    .with(
                        jwt()
                            .jwt(token -> token.subject(source.holderId().toString()))
                            .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            claim(fixture.materialId(), fixture.squadronAId(), amount))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readValue(body, ClaimDto.class);
  }

  private StockSnapshot readStock(StockFixture source, UUID order) {
    return Objects.requireNonNull(
        transactionTemplate.execute(_ -> {
          var row = stockReads.findRow(source.rowId()).orElseThrow();
          return new StockSnapshot(
              row.getAmount(), row.getVersion(), row.jobOrderSliceAmount(order));
        }));
  }

  /** Immutable current facts copied from the owning Inventory read API inside its transaction. */
  private record StockSnapshot(double amount, long version, double allocated) {}
}
