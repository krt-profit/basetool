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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.joborder.api.ClaimBucketDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.ClaimDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderDto;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.core.type.TypeReference;

/**
 * Verifies overlapping quality capacity survives concurrent public demand, stock and promise
 * writes.
 */
@SpringBootTest
@WithMockUser(roles = "ADMIN")
class MaterialQualityCapacityRaceIntegrationTest extends MaterialContributionTestSupport {
  /** Retains capacity and independent contribution versions after one real PostgreSQL wait race. */
  @Test
  void overlappingQualityDemandStockAndPromiseWritesPreserveCapacityAndUnrelatedVersions(
      TestReporter reporter) throws Exception {
    Fixture fixture = seed();
    Fixture otherOrder = seed();
    UUID unrelatedMaterial =
        transactionTemplate.execute(_ -> {
          Material material = new Material();
          material.setName("Independent capacity " + UUID.randomUUID());
          material.setType(MaterialType.RAW);
          return materialRepository.saveAndFlush(material).getId();
        });
    List<CreateJobOrderMaterialDto> originalDemand =
        List.of(
            new CreateJobOrderMaterialDto(fixture.materialId(), 650, 6.0),
            new CreateJobOrderMaterialDto(fixture.materialId(), 0, 6.0),
            new CreateJobOrderMaterialDto(unrelatedMaterial, 650, 6.0));
    assertThat(
            http(
                    put("/api/v1/orders/{id}", fixture.orderId()),
                    demand(readOrder(fixture.orderId()), originalDemand))
                .getResponse()
                .getStatus())
        .isEqualTo(200);
    ClaimDto good =
        promise(
            fixture.orderId(),
            new CreateClaimDto(fixture.materialId(), "GOOD", fixture.squadronAId(), 6.0, null));
    ClaimDto none =
        promise(
            fixture.orderId(),
            new CreateClaimDto(fixture.materialId(), "NONE", fixture.squadronBId(), 6.0, null));
    ClaimDto independentMaterial =
        promise(
            fixture.orderId(),
            new CreateClaimDto(unrelatedMaterial, "GOOD", fixture.squadronAId(), 3.0, null));
    ClaimDto independentOrder =
        promise(
            otherOrder.orderId(),
            new CreateClaimDto(
                otherOrder.materialId(), "GOOD", otherOrder.squadronAId(), 4.0, null));
    var independentMaterialBefore =
        contributionHistory(fixture.orderId(), independentMaterial.id());
    var independentOrderBefore = contributionHistory(otherOrder.orderId(), independentOrder.id());
    StockFixture stock = stock(fixture, 4.0);
    long stockVersion = stockReads.findRow(stock.rowId()).orElseThrow().getVersion();
    var reducedDemand =
        demand(
            readOrder(fixture.orderId()),
            List.of(
                new CreateJobOrderMaterialDto(fixture.materialId(), 650, 2.0),
                new CreateJobOrderMaterialDto(fixture.materialId(), 0, 6.0),
                new CreateJobOrderMaterialDto(unrelatedMaterial, 650, 6.0)));
    var allocation =
        Map.of(
            "field",
            "JOB_ORDER",
            "targetId",
            fixture.orderId(),
            "amount",
            4.0,
            "version",
            stockVersion);
    var amountEdit =
        new CreateClaimDto(
            fixture.materialId(), "NONE", fixture.squadronBId(), 5.0, none.version());
    CountDownLatch demandWritten = new CountDownLatch(1);
    CountDownLatch commitDemand = new CountDownLatch(1);
    AtomicInteger demandPid = new AtomicInteger();
    AtomicInteger allocationPid = new AtomicInteger();
    try (var executor = Executors.newFixedThreadPool(3)) {
      var changingDemand =
          executor.submit(() ->
              transactionTemplate.execute(_ ->
                  assertDoesNotThrow(() -> {
                    demandPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    MvcResult result =
                        http(put("/api/v1/orders/{id}", fixture.orderId()), reducedDemand);
                    assertThat(result.getResponse().getStatus()).isEqualTo(200);
                    demandWritten.countDown();
                    assertThat(commitDemand.await(40, TimeUnit.SECONDS)).isTrue();
                    return result;
                  })));
      try {
        assertThat(demandWritten.await(15, TimeUnit.SECONDS)).isTrue();
        var allocating =
            executor.submit(() ->
                transactionTemplate.execute(_ ->
                    assertDoesNotThrow(() -> {
                      allocationPid.set(
                          jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                      return mvc()
                          .perform(
                              post("/api/v1/inventory/{id}/allocation", stock.rowId())
                                  .with(
                                      jwt()
                                          .jwt(token -> token.subject(stock.holderId().toString()))
                                          .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                                  .contentType(MediaType.APPLICATION_JSON)
                                  .content(json.writeValueAsString(allocation)))
                          .andReturn();
                    })));
        await()
            .atMost(Duration.ofSeconds(10))
            .until(() -> allocating.isDone() || blockedPids(demandPid.get()).size() == 1);
        assertThat(allocating.isDone()).isFalse();
        assertThat(blockedPids(demandPid.get())).containsExactly(allocationPid.get());
        var editingPromise =
            executor.submit(
                () -> http(post("/api/v1/orders/{id}/claims", fixture.orderId()), amountEdit));
        await()
            .atMost(Duration.ofSeconds(10))
            .until(() -> editingPromise.isDone() || blockedPids(demandPid.get()).size() == 2);
        assertThat(editingPromise.isDone()).isFalse();
        assertThat(blockedPids(demandPid.get()))
            .as("both public stock and promise transactions wait for the uncommitted demand")
            .hasSize(2)
            .contains(allocationPid.get());
        commitDemand.countDown();
        assertThat(changingDemand.get(15, TimeUnit.SECONDS).getResponse().getStatus())
            .isEqualTo(200);
        assertThat(allocating.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        MvcResult editedPromise = editingPromise.get(15, TimeUnit.SECONDS);
        reporter.publishEntry(
            "promiseWriteStatus", Integer.toString(editedPromise.getResponse().getStatus()));
        if (editedPromise.getResponse().getStatus() == 201) {
          ClaimDto edited =
              json.readValue(editedPromise.getResponse().getContentAsString(), ClaimDto.class);
          assertThat(edited.id()).isEqualTo(none.id());
          assertThat(edited.amount()).isEqualTo(5.0);
          assertThat(edited.version()).isGreaterThan(none.version());
        } else {
          assertThat(editedPromise.getResponse().getStatus()).isEqualTo(409);
          assertThat(
                  json.readTree(editedPromise.getResponse().getContentAsString())
                      .path("code")
                      .asString())
              .isEqualTo("OPTIMISTIC_LOCK");
        }
      } finally {
        commitDemand.countDown();
      }
    }
    JobOrderDto current = readOrder(fixture.orderId());
    var materialLines =
        current.materials().stream()
            .filter(line -> line.material().id().equals(fixture.materialId()))
            .toList();
    assertThat(materialLines).hasSize(2);

    assertThat(materialLines)
        .allSatisfy(line -> {
          assertThat(line.amount()).isNotNull().isGreaterThanOrEqualTo(0.0);
          assertThat(line.currentStock()).isNotNull().isGreaterThanOrEqualTo(0.0);
          assertThat(line.claims())
              .allSatisfy(claim -> assertThat(claim.amount()).isGreaterThanOrEqualTo(0.0));
          assertThat(line.claims().stream().mapToDouble(ClaimDto::amount).sum())
              .isLessThanOrEqualTo(line.amount() - line.currentStock());
          assertThat(line.currentStock()).isEqualTo(2.0);
        });
    assertThat(materialLines.stream().mapToDouble(line -> line.currentStock()).sum())
        .isEqualTo(4.0);
    ClaimBucketDto goodBucket = bucket(fixture.orderId(), fixture.materialId(), "GOOD");
    ClaimBucketDto noneBucket = bucket(fixture.orderId(), fixture.materialId(), "NONE");
    assertThat(goodBucket.requiredAmount()).isEqualTo(2.0);
    assertThat(goodBucket.claimedAmount()).isZero();
    assertThat(goodBucket.openRemaining()).isZero();
    assertThat(noneBucket.requiredAmount()).isEqualTo(6.0);
    assertThat(noneBucket.claimedAmount()).isEqualTo(4.0);
    assertThat(noneBucket.openRemaining()).isZero();
    assertThat(contributionHistory(fixture.orderId(), good.id()).openAmount()).isZero();
    assertThat(contributionHistory(fixture.orderId(), good.id()).creditedAmount()).isZero();
    assertThat(contributionHistory(fixture.orderId(), none.id()).openAmount()).isEqualTo(4.0);
    assertThat(contributionHistory(fixture.orderId(), none.id()).creditedAmount()).isZero();
    transactionTemplate.executeWithoutResult(_ -> {
      var row = stockReads.findRow(stock.rowId()).orElseThrow();
      assertThat(row.getAmount()).isEqualTo(4.0);
      assertThat(row.getQuality()).isEqualTo(650);
      assertThat(row.jobOrderSliceAmount(fixture.orderId())).isEqualTo(4.0);
      assertThat(row.jobOrderReserved()).isEqualTo(4.0);
    });
    assertThat(contributionHistory(fixture.orderId(), independentMaterial.id()))
        .isEqualTo(independentMaterialBefore);
    assertThat(contributionHistory(otherOrder.orderId(), independentOrder.id()))
        .isEqualTo(independentOrderBefore);
    ClaimDto editedMaterial =
        promise(
            fixture.orderId(),
            new CreateClaimDto(
                unrelatedMaterial,
                "GOOD",
                fixture.squadronAId(),
                4.0,
                independentMaterial.version()));
    ClaimDto editedOrder =
        promise(
            otherOrder.orderId(),
            new CreateClaimDto(
                otherOrder.materialId(),
                "GOOD",
                otherOrder.squadronAId(),
                5.0,
                independentOrder.version()));
    assertThat(editedMaterial.id()).isEqualTo(independentMaterial.id());
    assertThat(editedMaterial.amount()).isEqualTo(4.0);
    assertThat(editedMaterial.version()).isGreaterThan(independentMaterial.version());
    assertThat(editedOrder.id()).isEqualTo(independentOrder.id());
    assertThat(editedOrder.amount()).isEqualTo(5.0);
    assertThat(editedOrder.version()).isGreaterThan(independentOrder.version());
    assertThat(contributionHistory(fixture.orderId(), editedMaterial.id()).openAmount())
        .isEqualTo(4.0);
    assertThat(contributionHistory(otherOrder.orderId(), editedOrder.id()).openAmount())
        .isEqualTo(5.0);
  }

  /** Builds a complete material replacement with the observed order version. */
  private CreateJobOrderDto demand(
      JobOrderDto observed, List<CreateJobOrderMaterialDto> materials) {
    return new CreateJobOrderDto(
        null, null, observed.handle(), null, materials, observed.version());
  }

  /** Writes one deliberate promise through HTTP and returns its observed contribution version. */
  private ClaimDto promise(UUID orderId, CreateClaimDto payload) throws Exception {
    MvcResult result = http(post("/api/v1/orders/{id}/claims", orderId), payload);
    assertThat(result.getResponse().getStatus()).isEqualTo(201);
    return json.readValue(result.getResponse().getContentAsString(), ClaimDto.class);
  }

  /** Reads the committed public order projection with its current quality coverage. */
  private JobOrderDto readOrder(UUID orderId) throws Exception {
    MvcResult result = http(get("/api/v1/orders/{id}", orderId), null);
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    return json.readValue(result.getResponse().getContentAsString(), JobOrderDto.class);
  }

  /** Reads the selected public material-quality promise bucket. */
  private ClaimBucketDto bucket(UUID orderId, UUID materialId, String quality) throws Exception {
    MvcResult result = http(get("/api/v1/orders/{id}/claims", orderId), null);
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    List<ClaimBucketDto> buckets =
        json.readValue(
            result.getResponse().getContentAsString(),
            new TypeReference<List<ClaimBucketDto>>() {});
    return buckets.stream()
        .filter(row ->
            row.material().id().equals(materialId) && row.qualityRequirement().equals(quality))
        .findFirst()
        .orElseThrow();
  }

  /** Sends an administrator HTTP request through the real backend security filters. */
  private MvcResult http(MockHttpServletRequestBuilder request, @Nullable Object payload)
      throws Exception {
    request.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")));
    if (payload != null) {
      request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload));
    }
    return mvc().perform(request).andReturn();
  }

  /** Reads current-database backends blocked by the known uncommitted demand transaction. */
  private List<Integer> blockedPids(int blocker) {
    return jdbc.queryForList(
        "SELECT pid FROM pg_stat_activity WHERE datname = current_database()"
            + " AND ? = ANY(pg_blocking_pids(pid)) ORDER BY pid",
        Integer.class,
        blocker);
  }
}
