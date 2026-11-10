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
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;

/** Verifies newly requested material demand participates in the complete public write protocol. */
@SpringBootTest
@WithMockUser(roles = "ADMIN")
class MaterialOrderResourcePlanningIntegrationTest extends MaterialContributionTestSupport {
  @Test
  void replacingDemandSerializesNewMaterialProvisionUntilTheDemandCommit() throws Exception {
    Fixture fixture = seed();
    var old =
        materialClaimService.upsertClaim(
            fixture.orderId(), claim(fixture.materialId(), fixture.squadronAId(), 4.0));
    UUID replacementId =
        transactionTemplate.execute(_ -> {
          Material material = new Material();
          material.setName("Replacement material " + UUID.randomUUID());
          material.setType(MaterialType.RAW);
          return materialRepository.saveAndFlush(material).getId();
        });
    StockFixture fresh =
        stock(
            new Fixture(
                fixture.orderId(), replacementId, fixture.squadronAId(), fixture.squadronBId()),
            1.0);
    var order = jobOrderQueryService.getJobOrderById(fixture.orderId());
    var update =
        new CreateJobOrderDto(
            null,
            null,
            order.handle(),
            null,
            List.of(new CreateJobOrderMaterialDto(replacementId, 650, 10.0)),
            order.version());
    var allocation =
        Map.of(
            "field",
            "JOB_ORDER",
            "targetId",
            fixture.orderId(),
            "amount",
            1.0,
            "version",
            stockReads.findRow(fresh.rowId()).orElseThrow().getVersion());
    CountDownLatch edited = new CountDownLatch(1);
    CountDownLatch commitEdit = new CountDownLatch(1);
    AtomicInteger editPid = new AtomicInteger();
    AtomicInteger provisionPid = new AtomicInteger();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var editing =
          executor.submit(() ->
              transactionTemplate.execute(_ ->
                  assertDoesNotThrow(() -> {
                    editPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    int status =
                        mvc()
                            .perform(
                                put("/api/v1/orders/{id}", fixture.orderId())
                                    .with(
                                        jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(json.writeValueAsString(update)))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                    assertThat(status).isEqualTo(200);
                    edited.countDown();
                    assertThat(commitEdit.await(20, TimeUnit.SECONDS)).isTrue();
                    return status;
                  })));
      try {
        assertThat(edited.await(15, TimeUnit.SECONDS)).isTrue();
        var providing =
            executor.submit(() ->
                transactionTemplate.execute(_ ->
                    assertDoesNotThrow(() -> {
                      provisionPid.set(
                          jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                      return mvc()
                          .perform(
                              post("/api/v1/inventory/{id}/allocation", fresh.rowId())
                                  .with(
                                      jwt()
                                          .jwt(token -> token.subject(fresh.holderId().toString()))
                                          .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                                  .contentType(MediaType.APPLICATION_JSON)
                                  .content(json.writeValueAsString(allocation)))
                          .andReturn()
                          .getResponse()
                          .getStatus();
                    })));
        await()
            .atMost(Duration.ofSeconds(10))
            .until(() -> providing.isDone() || blockedBy(provisionPid.get(), editPid.get()));
        assertThat(blockedBy(provisionPid.get(), editPid.get()))
            .as("the new material provision waits for the outstanding demand edit")
            .isTrue();
        commitEdit.countDown();
        assertThat(editing.get(15, TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(providing.get(15, TimeUnit.SECONDS)).isEqualTo(200);
      } finally {
        commitEdit.countDown();
      }
    }
    var current = onlyBucket(fixture.orderId());
    assertThat(current.material().id()).isEqualTo(replacementId);
    assertThat(current.requiredAmount()).isEqualTo(10.0);
    assertThat(current.claimedAmount()).isZero();
    assertThat(current.openRemaining()).isEqualTo(9.0);
    assertThat(contributionHistory(fixture.orderId(), old.id()).openAmount()).isZero();
    assertThat(contributionHistory(fixture.orderId(), old.id()).endedAt()).isNotNull();
    transactionTemplate.executeWithoutResult(_ ->
        assertThat(
                stockReads
                    .findRow(fresh.rowId())
                    .orElseThrow()
                    .jobOrderSliceAmount(fixture.orderId()))
            .isEqualTo(1.0));
  }

  private boolean blockedBy(int waiter, int blocker) {
    return waiter > 0
        && Boolean.TRUE.equals(
            jdbc.queryForObject(
                "SELECT ? = ANY(pg_blocking_pids(?))", Boolean.class, blocker, waiter));
  }
}
