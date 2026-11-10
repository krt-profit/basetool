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
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventDto;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryRowVersionDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.JobOrderAllocationDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.MaterialCollectionEntryDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockBookingReason;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityProvenance;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.kernel.PageResponse;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.OrgUnitContextualAuthority;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.UnexpectedRollbackException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Verifies current Inventory authority for connected corrections through public operations. */
@SpringBootTest
@RequiredArgsConstructor(onConstructor_ = @Autowired)
@WithMockUser(roles = "ADMIN")
class InventoryMaterialQualityCorrectionIntegrationTest extends MaterialContributionTestSupport {
  @Autowired private OrgUnitMembershipRepository memberships;

  /** Reads exact physical quantity identity through the Inventory module. */
  private final StockQuantityProvenance quantity;

  @Test
  void orderWriterWithoutCurrentStockActionRightsCannotCorrectQuantity() throws Exception {
    Fixture fixture = seed();
    var original =
        materialClaimService.upsertClaim(
            fixture.orderId(), claim(fixture.materialId(), fixture.squadronAId(), 4.0));
    materialClaimService.upsertClaim(
        fixture.orderId(), claim(fixture.materialId(), fixture.squadronBId(), 6.0));
    StockFixture stock = stock(fixture, 3.0);
    provide(fixture, original, stock, 3.0);
    UUID responsible =
        transactionTemplate.execute(_ ->
            jobOrderRepository
                .findById(fixture.orderId())
                .orElseThrow()
                .getResponsibleOrgUnit()
                .getId());
    UUID actor = orderWriter(responsible, fixture.squadronBId(), stock.holderId());

    mvc()
        .perform(
            post("/api/v1/inventory/{id}/pool", stock.rowId())
                .with(
                    jwt()
                        .jwt(token -> token.subject(stock.holderId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.createObjectNode()
                        .put(
                            "version", stockReads.findRow(stock.rowId()).orElseThrow().getVersion())
                        .put("targetOwningOrgUnitId", fixture.squadronBId().toString())
                        .put("mergeStock", false)
                        .toString()))
        .andExpect(status().isOk());
    var before = contributionHistory(fixture.orderId(), original.id());
    var receipt = before.provisions().getFirst();
    JsonNode stockBefore = inventory(fixture, stock);
    Long stockVersion = stockBefore.get("version").asLong();
    assertThat(before.creditedAmount()).isEqualTo(3.0);
    assertThat(stockBefore.get("owningSquadron").get("id").asString())
        .isEqualTo(fixture.squadronBId().toString());

    mvc()
        .perform(
            get("/api/v1/inventory/material/{id}", fixture.materialId())
                .with(asOrderWriter(actor, responsible)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].id").value(stock.rowId().toString()))
        .andExpect(jsonPath("$.content[0].canEdit").value(false));
    String actorHistory =
        mvc()
            .perform(
                get("/api/v1/orders/{id}/claims/history", fixture.orderId())
                    .with(asOrderWriter(actor, responsible)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    List<de.greluc.krt.profit.basetool.backend.joborder.api.MaterialContributionHistoryDto>
        readable =
            json.readValue(
                actorHistory,
                new tools.jackson.core.type.TypeReference<
                    List<
                        de.greluc.krt.profit.basetool.backend.joborder.api
                            .MaterialContributionHistoryDto>>() {});
    assertThat(
            readable.stream()
                .flatMap(row -> row.provisions().stream())
                .filter(row -> row.id().equals(receipt.id()))
                .findFirst()
                .orElseThrow()
                .canCorrect())
        .isTrue();

    mvc()
        .perform(
            patch("/api/v1/inventory/{id}/allocation", stock.rowId())
                .with(asOrderWriter(actor, responsible))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.createObjectNode()
                        .put("field", "JOB_ORDER")
                        .put("targetId", fixture.orderId().toString())
                        .put("amount", 2.0)
                        .put("version", stockVersion)
                        .toString()))
        .andExpect(status().isForbidden());
    assertThat(inventory(fixture, stock)).isEqualTo(stockBefore);
    assertThat(contributionHistory(fixture.orderId(), original.id())).isEqualTo(before);

    var correction =
        new MaterialProvisionCorrectionDto(
            MaterialProvisionCorrectionDto.Kind.QUANTITY,
            receipt.version(),
            before.version(),
            2.0,
            null,
            null,
            List.of(new InventoryRowVersionDto(stock.rowId(), stockVersion)),
            null,
            null,
            List.of());
    mvc()
        .perform(
            post(
                    "/api/v1/orders/{order}/claims/provisions/{receipt}/correction",
                    fixture.orderId(),
                    receipt.id())
                .with(asOrderWriter(actor, responsible))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(correction)))
        .andExpect(result ->
            assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(403));

    assertThat(inventory(fixture, stock)).isEqualTo(stockBefore);
    assertThat(contributionHistory(fixture.orderId(), original.id())).isEqualTo(before);
  }

  @Test
  void selectedPieceQualitySplitsOnlyWholeDefaultAllocationShares() throws Exception {
    Fixture fixture = seed();
    transactionTemplate.executeWithoutResult(_ -> {
      var material = materialRepository.findById(fixture.materialId()).orElseThrow();
      material.setQuantityType(QuantityType.PIECE);
      materialRepository.saveAndFlush(material);
    });
    var original =
        materialClaimService.upsertClaim(
            fixture.orderId(), claim(fixture.materialId(), fixture.squadronAId(), 2.0));
    materialClaimService.upsertClaim(
        fixture.orderId(), claim(fixture.materialId(), fixture.squadronBId(), 8.0));
    StockFixture source = stock(fixture, 2.0);
    provide(fixture, original, source, 2.0);
    UUID merged =
        transactionTemplate.execute(_ -> {
          var row = stockReads.findRow(source.rowId()).orElseThrow();
          return stockCommands.bookIntoPool(
              source.holderId(),
              row.getOwningOrgUnit(),
              new StockLot(row.getMaterial(), null, row.getLocation(), 650, false),
              1.0,
              StockBookingReason.PURCHASE);
        });
    assertThat(merged).isNotEqualTo(source.rowId());
    assertThat(stockReads.findRow(source.rowId())).isEmpty();
    UUID secondOrder = additionalMaterialOrder(fixture);
    onePieceAllocation(source, merged, fixture.orderId(), true);
    onePieceAllocation(source, merged, secondOrder, false);
    mvc()
        .perform(
            patch("/api/v1/inventory/{id}/delivered", merged)
                .with(
                    jwt()
                        .jwt(token -> token.subject(source.holderId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.createObjectNode()
                        .put("jobOrderId", fixture.orderId().toString())
                        .put("delivered", true)
                        .put("version", stockReads.findRow(merged).orElseThrow().getVersion())
                        .toString()))
        .andExpect(status().isOk());
    StockLot originalLot =
        transactionTemplate.execute(_ -> {
          var row = stockReads.findRow(merged).orElseThrow();
          return new StockLot(row.getMaterial(), null, row.getLocation(), 650, false);
        });
    var before = contributionHistory(fixture.orderId(), original.id());
    var receipt = before.provisions().getFirst();
    var correction =
        json.createObjectNode()
            .put("kind", "QUALITY")
            .put("version", receipt.version())
            .put("claimVersion", before.version())
            .put("amount", 2.0)
            .put("targetClaimId", original.id().toString())
            .put("targetClaimVersion", before.version())
            .put("suppliedQuality", 700);
    correction.putArray("qualityStockRowIds").add(merged.toString());
    correction
        .putArray("stockVersions")
        .addObject()
        .put("id", merged.toString())
        .put("version", stockReads.findRow(merged).orElseThrow().getVersion());
    mvc()
        .perform(
            post(
                    "/api/v1/orders/{order}/claims/provisions/{receipt}/correction",
                    fixture.orderId(),
                    receipt.id())
                .with(
                    jwt()
                        .jwt(token -> token.subject(source.holderId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(correction.toString()))
        .andExpect(result ->
            assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200));

    UUID correctedTarget =
        fixture.orderId().compareTo(secondOrder) < 0 ? fixture.orderId() : secondOrder;
    UUID remainingTarget =
        correctedTarget.equals(fixture.orderId()) ? secondOrder : fixture.orderId();
    StockLot correctedLot =
        new StockLot(originalLot.material(), null, originalLot.location(), 700, false);
    var current = stockReads.lotRows(source.holderId(), List.of(originalLot, correctedLot));
    assertThat(current.get(originalLot.key()))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.getQuality()).isEqualTo(650);
          assertThat(row.getAmount()).isEqualTo(1.0);
          assertThat(row.jobOrderReserved()).isEqualTo(1.0);
          assertThat(row.jobOrderSliceAmount(remainingTarget)).isEqualTo(1.0);
          assertThat(row.jobOrderSliceAmount(correctedTarget)).isZero();
        });
    assertThat(current.get(correctedLot.key()))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.getQuality()).isEqualTo(700);
          assertThat(row.getAmount()).isEqualTo(2.0);
          assertThat(row.jobOrderReserved()).isEqualTo(1.0);
          assertThat(row.jobOrderSliceAmount(correctedTarget)).isEqualTo(1.0);
          assertThat(row.jobOrderSliceAmount(remainingTarget)).isZero();
        });
    assertPieceCollection(
        fixture.orderId(),
        source.holderId(),
        correctedTarget.equals(fixture.orderId()) ? 700.0 : 650.0,
        true);
    assertPieceCollection(
        secondOrder, source.holderId(), correctedTarget.equals(secondOrder) ? 700.0 : 650.0, false);
    mvc()
        .perform(
            get(
                    "/api/v1/orders/{order}/claims/provisions/{receipt}/quality-options",
                    fixture.orderId(),
                    receipt.id())
                .with(
                    jwt()
                        .jwt(token -> token.subject(source.holderId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].quality").value(700))
        .andExpect(jsonPath("$[0].amount").value(2.0))
        .andExpect(jsonPath("$[0].affectedAmount").value(2.0));
    var after = contributionHistory(fixture.orderId(), original.id());
    assertThat(after.creditedAmount()).isEqualTo(2.0);
    assertThat(after.provisions().getFirst().quantityAmount()).isEqualTo(2.0);
    assertThat(after.provisions().getFirst().suppliedQuality()).isEqualTo(700);
  }

  @Test
  void fullDiscardStillRemovesAnExistingFractionalWholeUnitRow() throws Exception {
    Fixture fixture = seed();
    StockFixture stock = stock(fixture, 1.5);
    transactionTemplate.executeWithoutResult(_ -> {
      var material = materialRepository.findById(fixture.materialId()).orElseThrow();
      material.setQuantityType(QuantityType.PIECE);
      materialRepository.saveAndFlush(material);
    });
    JsonNode before = inventory(fixture, stock);
    assertThat(before.get("material").get("quantityType").asString()).isEqualTo("PIECE");
    assertThat(before.get("amount").asDouble()).isEqualTo(1.5);
    assertThat(before.get("jobOrderAllocations").size()).isZero();
    assertThat(before.get("missionAllocations").size()).isZero();
    mvc()
        .perform(
            post("/api/v1/inventory/{id}/book-out", stock.rowId())
                .with(
                    jwt()
                        .jwt(token -> token.subject(stock.holderId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.createObjectNode()
                        .put("type", "DISCARD")
                        .put("amount", 0.5)
                        .put("version", before.get("version").asLong())
                        .toString()))
        .andExpect(status().isBadRequest());
    assertThat(inventory(fixture, stock)).isEqualTo(before);

    mvc()
        .perform(
            post("/api/v1/inventory/{id}/book-out", stock.rowId())
                .with(
                    jwt()
                        .jwt(token -> token.subject(stock.holderId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.createObjectNode()
                        .put("type", "DISCARD")
                        .put("amount", 1.5)
                        .put("version", before.get("version").asLong())
                        .toString()))
        .andExpect(result ->
            assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(204));
    assertThat(stockReads.findRow(stock.rowId())).isEmpty();
    mvc()
        .perform(
            get("/api/v1/inventory/material/{id}", fixture.materialId())
                .with(
                    jwt()
                        .jwt(token -> token.subject(stock.holderId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(0));
  }

  @Test
  void wholeDiscardPreservesTheFractionalRestOfAnExistingWholeUnitRow() throws Exception {
    Fixture fixture = seed();
    StockFixture stock = stock(fixture, 2.5);
    transactionTemplate.executeWithoutResult(_ -> {
      var material = materialRepository.findById(fixture.materialId()).orElseThrow();
      material.setQuantityType(QuantityType.PIECE);
      materialRepository.saveAndFlush(material);
    });
    JsonNode before = inventory(fixture, stock);
    assertThat(before.get("material").get("quantityType").asString()).isEqualTo("PIECE");
    assertThat(before.get("amount").asDouble()).isEqualTo(2.5);
    assertThat(before.get("jobOrderAllocations").size()).isZero();
    assertThat(before.get("missionAllocations").size()).isZero();
    mvc()
        .perform(
            post("/api/v1/inventory/{id}/book-out", stock.rowId())
                .with(
                    jwt()
                        .jwt(token -> token.subject(stock.holderId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.createObjectNode()
                        .put("type", "DISCARD")
                        .put("amount", 1.0)
                        .put("version", before.get("version").asLong())
                        .toString()))
        .andExpect(result ->
            assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200));
    JsonNode after = inventory(fixture, stock);
    assertThat(after.get("amount").asDouble()).isEqualTo(1.5);
    assertThat(after.get("jobOrderRest").asDouble()).isEqualTo(1.5);
    assertThat(after.get("missionRest").asDouble()).isEqualTo(1.5);
    assertThat(after.get("material")).isEqualTo(before.get("material"));
    assertThat(after.get("location")).isEqualTo(before.get("location"));
    assertThat(after.get("user")).isEqualTo(before.get("user"));
    assertThat(after.get("owningSquadron")).isEqualTo(before.get("owningSquadron"));
    assertThat(after.get("quality")).isEqualTo(before.get("quality"));
    assertThat(after.get("personal")).isEqualTo(before.get("personal"));
    assertThat(after.get("stolen")).isEqualTo(before.get("stolen"));
    assertThat(after.get("version").asLong()).isGreaterThan(before.get("version").asLong());
    assertThat(stockReads.findRow(stock.rowId()).orElseThrow().getAmount()).isEqualTo(1.5);
  }

  @Test
  void orderWriterWithoutStockRightsCanOnlyCorrectHistoricalQuality() throws Exception {
    Fixture fixture = seed();
    var original =
        materialClaimService.upsertClaim(
            fixture.orderId(), claim(fixture.materialId(), fixture.squadronAId(), 4.0));
    materialClaimService.upsertClaim(
        fixture.orderId(), claim(fixture.materialId(), fixture.squadronBId(), 6.0));
    StockFixture stock = stock(fixture, 3.0);
    provide(fixture, original, stock, 3.0);
    UUID responsible =
        transactionTemplate.execute(_ ->
            jobOrderRepository
                .findById(fixture.orderId())
                .orElseThrow()
                .getResponsibleOrgUnit()
                .getId());
    UUID actor = orderWriter(responsible, fixture.squadronBId(), stock.holderId());
    mvc()
        .perform(
            post("/api/v1/inventory/{id}/pool", stock.rowId())
                .with(
                    jwt()
                        .jwt(token -> token.subject(stock.holderId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.createObjectNode()
                        .put(
                            "version", stockReads.findRow(stock.rowId()).orElseThrow().getVersion())
                        .put("targetOwningOrgUnitId", fixture.squadronBId().toString())
                        .put("mergeStock", false)
                        .toString()))
        .andExpect(status().isOk());
    JsonNode stockBefore = inventory(fixture, stock);
    var before = contributionHistory(fixture.orderId(), original.id());
    var receipt = before.provisions().getFirst();
    assertThat(stockBefore.get("quality").asInt()).isEqualTo(650);
    assertThat(stockBefore.get("amount").asDouble()).isEqualTo(3.0);
    mvc()
        .perform(
            get(
                    "/api/v1/orders/{order}/claims/provisions/{receipt}/quality-options",
                    fixture.orderId(),
                    receipt.id())
                .with(asOrderWriter(actor, responsible)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
    assertThat(inventory(fixture, stock)).isEqualTo(stockBefore);
    assertThat(contributionHistory(fixture.orderId(), original.id())).isEqualTo(before);

    var correction =
        json.createObjectNode()
            .put("kind", "QUALITY")
            .put("version", receipt.version())
            .put("claimVersion", before.version())
            .put("amount", 3.0)
            .put("targetClaimId", original.id().toString())
            .put("targetClaimVersion", before.version())
            .put("suppliedQuality", 700);
    correction.putArray("qualityStockRowIds").add(stock.rowId().toString());
    correction
        .putArray("stockVersions")
        .addObject()
        .put("id", stock.rowId().toString())
        .put("version", Math.addExact(stockBefore.get("version").asLong(), 1L));
    mvc()
        .perform(
            post(
                    "/api/v1/orders/{order}/claims/provisions/{receipt}/correction",
                    fixture.orderId(),
                    receipt.id())
                .with(asOrderWriter(actor, responsible))
                .contentType(MediaType.APPLICATION_JSON)
                .content(correction.toString()))
        .andExpect(result ->
            assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(403));
    assertThat(inventory(fixture, stock)).isEqualTo(stockBefore);
    assertThat(contributionHistory(fixture.orderId(), original.id())).isEqualTo(before);

    correction.putArray("qualityStockRowIds");
    correction.putArray("stockVersions");
    mvc()
        .perform(
            post(
                    "/api/v1/orders/{order}/claims/provisions/{receipt}/correction",
                    fixture.orderId(),
                    receipt.id())
                .with(asOrderWriter(actor, responsible))
                .contentType(MediaType.APPLICATION_JSON)
                .content(correction.toString()))
        .andExpect(result ->
            assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200));
    assertThat(inventory(fixture, stock)).isEqualTo(stockBefore);
    var after = contributionHistory(fixture.orderId(), original.id());
    assertThat(after.creditedAmount()).isEqualTo(3.0);
    assertThat(after.provisions().getFirst().quantityAmount()).isEqualTo(3.0);
    assertThat(after.provisions().getFirst().suppliedQuality()).isEqualTo(700);
  }

  @Test
  void selectedPieceQualityMergesIntoExistingStockAndAuditsTheActualSurvivor() throws Exception {
    Fixture fixture = seed();
    transactionTemplate.executeWithoutResult(_ -> {
      var material = materialRepository.findById(fixture.materialId()).orElseThrow();
      material.setQuantityType(QuantityType.PIECE);
      materialRepository.saveAndFlush(material);
    });
    var original =
        materialClaimService.upsertClaim(
            fixture.orderId(), claim(fixture.materialId(), fixture.squadronAId(), 2.0));
    materialClaimService.upsertClaim(
        fixture.orderId(), claim(fixture.materialId(), fixture.squadronBId(), 8.0));
    StockFixture source = stock(fixture, 2.0);
    provide(fixture, original, source, 2.0);
    UUID merged =
        transactionTemplate.execute(_ -> {
          var row = stockReads.findRow(source.rowId()).orElseThrow();
          return stockCommands.bookIntoPool(
              source.holderId(),
              row.getOwningOrgUnit(),
              new StockLot(row.getMaterial(), null, row.getLocation(), 650, false),
              1.0,
              StockBookingReason.PURCHASE);
        });
    assertThat(merged).isNotEqualTo(source.rowId());
    UUID secondOrder = additionalMaterialOrder(fixture);
    onePieceAllocation(source, merged, fixture.orderId(), true);
    onePieceAllocation(source, merged, secondOrder, false);
    mvc()
        .perform(
            patch("/api/v1/inventory/{id}/delivered", merged)
                .with(asStockOwner(source))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.createObjectNode()
                        .put("jobOrderId", fixture.orderId().toString())
                        .put("delivered", true)
                        .put("version", stockReads.findRow(merged).orElseThrow().getVersion())
                        .toString()))
        .andExpect(status().isOk());
    UUID existingTarget =
        transactionTemplate.execute(_ -> {
          var row = stockReads.findRow(merged).orElseThrow();
          return stockCommands.bookIntoPool(
              source.holderId(),
              row.getOwningOrgUnit(),
              new StockLot(row.getMaterial(), null, row.getLocation(), 700, false),
              2.0,
              StockBookingReason.PURCHASE);
        });
    onePieceAllocation(source, existingTarget, fixture.orderId(), false);
    onePieceAllocation(source, existingTarget, secondOrder, false);
    var inventoryBefore = materialInventory(fixture, source);
    assertThat(inventoryBefore).hasSize(2);
    var selected =
        inventoryBefore.stream().filter(row -> row.id().equals(merged)).findFirst().orElseThrow();
    assertThat(selected.quality()).isEqualTo(650);
    assertThat(selected.amount()).isEqualTo(3.0);
    assertThat(inventoryBefore)
        .anySatisfy(row -> {
          assertThat(row.id()).isEqualTo(existingTarget);
          assertThat(row.quality()).isEqualTo(700);
          assertThat(row.amount()).isEqualTo(2.0);
        });
    var before = contributionHistory(fixture.orderId(), original.id());
    var receipt = before.provisions().getFirst();
    var firstCollection = materialCollection(fixture.orderId(), source);
    var secondCollection = materialCollection(secondOrder, source);
    var auditBefore = inventoryEvents(source, AuditEventType.INVENTORY_QUALITY_CORRECTED);
    var mergesBefore = inventoryEvents(source, AuditEventType.INVENTORY_ITEM_MERGED);
    assertThat(auditBefore).isEmpty();
    assertThat(mergesBefore).isEmpty();
    var correction =
        json.createObjectNode()
            .put("kind", "QUALITY")
            .put("version", receipt.version())
            .put("claimVersion", before.version())
            .put("amount", 2.0)
            .put("targetClaimId", original.id().toString())
            .put("targetClaimVersion", before.version())
            .put("suppliedQuality", 700);
    correction.putArray("qualityStockRowIds").add(merged.toString());
    correction
        .putArray("stockVersions")
        .addObject()
        .put("id", merged.toString())
        .put("version", Math.addExact(selected.version(), 1L));

    correctQuality(fixture, source, receipt.id(), correction, 409);

    assertThat(materialInventory(fixture, source)).isEqualTo(inventoryBefore);
    assertThat(contributionHistory(fixture.orderId(), original.id())).isEqualTo(before);
    assertThat(materialCollection(fixture.orderId(), source)).isEqualTo(firstCollection);
    assertThat(materialCollection(secondOrder, source)).isEqualTo(secondCollection);
    assertThat(inventoryEvents(source, AuditEventType.INVENTORY_QUALITY_CORRECTED))
        .isEqualTo(auditBefore);
    assertThat(inventoryEvents(source, AuditEventType.INVENTORY_ITEM_MERGED))
        .isEqualTo(mergesBefore);

    correction
        .putArray("stockVersions")
        .addObject()
        .put("id", merged.toString())
        .put("version", selected.version());
    correctQuality(fixture, source, receipt.id(), correction, 200);

    var inventoryAfter = materialInventory(fixture, source);
    assertThat(inventoryAfter).hasSize(2);
    var remaining =
        inventoryAfter.stream().filter(row -> row.quality() == 650).findFirst().orElseThrow();
    var corrected =
        inventoryAfter.stream().filter(row -> row.quality() == 700).findFirst().orElseThrow();
    assertThat(remaining.id()).isEqualTo(merged);
    assertThat(remaining.amount()).isEqualTo(1.0);
    assertThat(corrected.id()).isNotIn(merged, existingTarget, source.rowId());
    assertThat(corrected.amount()).isEqualTo(4.0);
    assertThat(inventoryAfter).extracting(InventoryItemDto::id).doesNotContain(existingTarget);
    UUID correctedTarget =
        fixture.orderId().compareTo(secondOrder) < 0 ? fixture.orderId() : secondOrder;
    UUID remainingTarget =
        correctedTarget.equals(fixture.orderId()) ? secondOrder : fixture.orderId();
    assertThat(remaining.jobOrderAllocations())
        .extracting(JobOrderAllocationDto::jobOrderId, JobOrderAllocationDto::amount)
        .containsExactly(tuple(remainingTarget, 1.0));
    assertThat(corrected.jobOrderAllocations())
        .extracting(JobOrderAllocationDto::jobOrderId, JobOrderAllocationDto::amount)
        .containsExactlyInAnyOrder(tuple(correctedTarget, 2.0), tuple(remainingTarget, 1.0));
    assertThat(remaining.jobOrderRest()).isZero();
    assertThat(corrected.jobOrderRest()).isEqualTo(1.0);
    var correctedCollection = materialCollection(correctedTarget, source);
    var remainingCollection = materialCollection(remainingTarget, source);
    assertThat(correctedCollection)
        .singleElement()
        .satisfies(row -> {
          assertThat(row.inventoryEntryId()).isEqualTo(corrected.id());
          assertThat(row.quantity()).isEqualTo(4.0);
          assertThat(row.quality()).isEqualTo(700.0);
          assertThat(row.allocatedQuantity()).isEqualTo(2.0);
          assertThat(row.delivered()).isEqualTo(correctedTarget.equals(fixture.orderId()));
        });
    assertThat(remainingCollection)
        .hasSize(2)
        .anySatisfy(row -> {
          assertThat(row.inventoryEntryId()).isEqualTo(remaining.id());
          assertThat(row.quantity()).isEqualTo(1.0);
          assertThat(row.quality()).isEqualTo(650.0);
          assertThat(row.allocatedQuantity()).isEqualTo(1.0);
          assertThat(row.delivered()).isEqualTo(remainingTarget.equals(fixture.orderId()));
        })
        .anySatisfy(row -> {
          assertThat(row.inventoryEntryId()).isEqualTo(corrected.id());
          assertThat(row.quantity()).isEqualTo(4.0);
          assertThat(row.quality()).isEqualTo(700.0);
          assertThat(row.allocatedQuantity()).isEqualTo(1.0);
          assertThat(row.delivered()).isFalse();
        });
    mvc()
        .perform(
            get(
                    "/api/v1/orders/{order}/claims/provisions/{receipt}/quality-options",
                    fixture.orderId(),
                    receipt.id())
                .with(asStockOwner(source)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(corrected.id().toString()))
        .andExpect(jsonPath("$[0].quality").value(700))
        .andExpect(jsonPath("$[0].amount").value(4.0))
        .andExpect(jsonPath("$[0].affectedAmount").value(2.0));
    var after = contributionHistory(fixture.orderId(), original.id());
    var correctedReceipt = after.provisions().getFirst();
    assertThat(after.creditedAmount()).isEqualTo(2.0);
    assertThat(correctedReceipt.id()).isEqualTo(receipt.id());
    assertThat(correctedReceipt.quantityAmount()).isEqualTo(2.0);
    assertThat(correctedReceipt.suppliedQuality()).isEqualTo(700);
    assertThat(correctedReceipt.factChanges()).hasSize(2);
    assertThat(correctedReceipt.factChanges().getFirst())
        .isEqualTo(receipt.factChanges().getFirst());
    var qualityEvents = inventoryEvents(source, AuditEventType.INVENTORY_QUALITY_CORRECTED);
    assertThat(qualityEvents)
        .singleElement()
        .satisfies(event -> {
          assertThat(event.subjectId()).isEqualTo(merged);
          assertThat(event.details().split(" "))
              .containsExactlyInAnyOrder(
                  "reason=FALSE_FACT_CORRECTED",
                  "provision=" + receipt.id(),
                  "previousQuality=650",
                  "quality=700",
                  "amount=2.0",
                  "correctedRow=" + corrected.id());
        });
    var mergeEvents = inventoryEvents(source, AuditEventType.INVENTORY_ITEM_MERGED);
    assertThat(mergeEvents)
        .singleElement()
        .satisfies(event -> {
          assertThat(event.subjectId()).isEqualTo(corrected.id());
          assertThat(event.details().split(" "))
              .containsExactlyInAnyOrder("merged=1", "total=4.0", "q=700", "trigger=auto");
        });

    correction.put("version", correctedReceipt.version());
    correction.put("claimVersion", after.version());
    correction.put("amount", 0.0);
    correction.putNull("targetClaimId");
    correction.putNull("targetClaimVersion");
    correction.putArray("qualityStockRowIds").add(corrected.id().toString());
    correction
        .putArray("stockVersions")
        .addObject()
        .put("id", corrected.id().toString())
        .put("version", corrected.version());
    correctQuality(fixture, source, receipt.id(), correction, 200);

    assertThat(materialInventory(fixture, source)).isEqualTo(inventoryAfter);
    assertThat(materialCollection(correctedTarget, source)).isEqualTo(correctedCollection);
    assertThat(materialCollection(remainingTarget, source)).isEqualTo(remainingCollection);
    var historicalOnly = contributionHistory(fixture.orderId(), original.id());
    assertThat(historicalOnly.creditedAmount()).isZero();
    var historicalReceipt = historicalOnly.provisions().getFirst();
    assertThat(historicalReceipt.id()).isEqualTo(receipt.id());
    assertThat(historicalReceipt.amount()).isZero();
    assertThat(historicalReceipt.quantityAmount()).isEqualTo(2.0);
    assertThat(historicalReceipt.suppliedQuality()).isEqualTo(700);
    assertThat(historicalReceipt.factChanges()).hasSize(3);
    assertThat(historicalReceipt.factChanges().subList(0, correctedReceipt.factChanges().size()))
        .isEqualTo(correctedReceipt.factChanges());
    assertThat(inventoryEvents(source, AuditEventType.INVENTORY_QUALITY_CORRECTED))
        .isEqualTo(qualityEvents);
    assertThat(inventoryEvents(source, AuditEventType.INVENTORY_ITEM_MERGED))
        .isEqualTo(mergeEvents);
  }

  @Test
  void currentAuthorityIsRecheckedAfterARealQualityCorrectionWait() throws Exception {
    Fixture fixture = seed();
    var original =
        materialClaimService.upsertClaim(
            fixture.orderId(), claim(fixture.materialId(), fixture.squadronAId(), 4.0));
    var other =
        materialClaimService.upsertClaim(
            fixture.orderId(), claim(fixture.materialId(), fixture.squadronBId(), 6.0));
    StockFixture stock = stock(fixture, 3.0);
    provide(fixture, original, stock, 3.0);
    UUID responsible =
        jobOrderQueryService.getJobOrderById(fixture.orderId()).responsibleOrgUnit().id();
    UUID actor = orderWriter(responsible, fixture.squadronBId(), stock.holderId());
    var originalBefore = contributionHistory(fixture.orderId(), original.id());
    var otherBefore = contributionHistory(fixture.orderId(), other.id());
    var orderBefore = jobOrderQueryService.getJobOrderById(fixture.orderId());
    var bucketBefore = onlyBucket(fixture.orderId());
    var receipt = originalBefore.provisions().getFirst();
    var intervals = quantity.quantityOf(stock.rowId());
    assertThat(intervals)
        .singleElement()
        .satisfies(fragment -> {
          assertThat(fragment.fromQuanta()).isZero();
          assertThat(fragment.toQuanta()).isEqualTo(3000L);
        });
    JsonNode stockBefore = inventory(fixture, stock);
    assertThat(stockBefore.get("quality").asInt()).isEqualTo(650);
    assertThat(stockBefore.get("amount").asDouble()).isEqualTo(3.0);
    mvc()
        .perform(
            get(
                    "/api/v1/orders/{order}/claims/provisions/{receipt}/quality-options",
                    fixture.orderId(),
                    receipt.id())
                .with(asOrderWriter(actor, responsible)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(stock.rowId().toString()))
        .andExpect(jsonPath("$[0].version").value(stockBefore.get("version").asLong()))
        .andExpect(jsonPath("$[0].affectedAmount").value(3.0));
    var correction =
        json.createObjectNode()
            .put("kind", "QUALITY")
            .put("version", receipt.version())
            .put("claimVersion", originalBefore.version())
            .put("amount", 3.0)
            .put("targetClaimId", original.id().toString())
            .put("targetClaimVersion", originalBefore.version())
            .put("suppliedQuality", 700);
    correction.putArray("qualityStockRowIds").add(stock.rowId().toString());
    correction
        .putArray("stockVersions")
        .addObject()
        .put("id", stock.rowId().toString())
        .put("version", stockBefore.get("version").asLong());
    JsonNode moved =
        qualityCorrectionAfterStockWait(
            fixture,
            stock,
            receipt.id(),
            correction.toString(),
            asOrderWriter(actor, responsible),
            403,
            () ->
                json.readTree(
                    mvc()
                        .perform(
                            post("/api/v1/inventory/{id}/pool", stock.rowId())
                                .with(asStockOwner(stock))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                    json.createObjectNode()
                                        .put("version", stockBefore.get("version").asLong())
                                        .put(
                                            "targetOwningOrgUnitId",
                                            fixture.squadronBId().toString())
                                        .put("mergeStock", false)
                                        .toString()))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString()));
    assertThat(moved.get("version").asLong()).isGreaterThan(stockBefore.get("version").asLong());
    assertThat(moved.get("owningSquadron").get("id").asString())
        .isEqualTo(fixture.squadronBId().toString());
    assertThat(inventory(fixture, stock)).isEqualTo(moved);
    assertThat(quantity.quantityOf(stock.rowId())).isEqualTo(intervals);
    assertThat(contributionHistory(fixture.orderId(), original.id())).isEqualTo(originalBefore);
    assertThat(contributionHistory(fixture.orderId(), other.id())).isEqualTo(otherBefore);
    assertThat(jobOrderQueryService.getJobOrderById(fixture.orderId())).isEqualTo(orderBefore);
    assertThat(onlyBucket(fixture.orderId())).isEqualTo(bucketBefore);

    correction
        .putArray("stockVersions")
        .addObject()
        .put("id", stock.rowId().toString())
        .put("version", moved.get("version").asLong());
    JsonNode noted =
        qualityCorrectionAfterStockWait(
            fixture,
            stock,
            receipt.id(),
            correction.toString(),
            asStockOwner(stock),
            409,
            () -> {
              JsonNode result =
                  json.readTree(
                      mvc()
                          .perform(
                              put("/api/v1/inventory/{id}/note", stock.rowId())
                                  .with(asStockOwner(stock))
                                  .contentType(MediaType.APPLICATION_JSON)
                                  .content(
                                      json.createObjectNode()
                                          .put("version", moved.get("version").asLong())
                                          .put("note", "Current stock note")
                                          .toString()))
                          .andExpect(status().isOk())
                          .andReturn()
                          .getResponse()
                          .getContentAsString());
              assertThat(result.get("version").asLong())
                  .isGreaterThan(moved.get("version").asLong());
              return result;
            });
    assertThat(noted.get("version").asLong()).isGreaterThan(moved.get("version").asLong());
    assertThat(noted.get("quality").asInt()).isEqualTo(650);
    assertThat(noted.get("amount").asDouble()).isEqualTo(3.0);
    assertThat(noted.get("note").asString()).isEqualTo("Current stock note");
    assertThat(inventory(fixture, stock)).isEqualTo(noted);
    assertThat(quantity.quantityOf(stock.rowId())).isEqualTo(intervals);
    assertThat(contributionHistory(fixture.orderId(), original.id())).isEqualTo(originalBefore);
    assertThat(contributionHistory(fixture.orderId(), other.id())).isEqualTo(otherBefore);
    assertThat(jobOrderQueryService.getJobOrderById(fixture.orderId())).isEqualTo(orderBefore);
    assertThat(onlyBucket(fixture.orderId())).isEqualTo(bucketBefore);
    assertThat(materialCollection(fixture.orderId(), stock))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.inventoryEntryId()).isEqualTo(stock.rowId());
          assertThat(row.allocatedQuantity()).isEqualTo(3.0);
          assertThat(row.quantity()).isEqualTo(3.0);
          assertThat(row.quality()).isEqualTo(650.0);
          assertThat(row.delivered()).isFalse();
        });

    JsonNode refreshed = inventory(fixture, stock);
    correction
        .putArray("stockVersions")
        .addObject()
        .put("id", stock.rowId().toString())
        .put("version", refreshed.get("version").asLong());
    mvc()
        .perform(
            post(
                    "/api/v1/orders/{order}/claims/provisions/{receipt}/correction",
                    fixture.orderId(),
                    receipt.id())
                .with(asStockOwner(stock))
                .contentType(MediaType.APPLICATION_JSON)
                .content(correction.toString()))
        .andExpect(status().isOk());
    JsonNode corrected = inventory(fixture, stock);
    assertThat(corrected.get("quality").asInt()).isEqualTo(700);
    assertThat(corrected.get("version").asLong()).isGreaterThan(refreshed.get("version").asLong());
    for (String field :
        List.of(
            "id",
            "user",
            "material",
            "gameItem",
            "location",
            "amount",
            "personal",
            "stolen",
            "jobOrderAllocations",
            "jobOrderRest",
            "missionAllocations",
            "missionRest",
            "donationAllocations",
            "note",
            "owningSquadron",
            "createdAt")) {
      assertThat(corrected.get(field)).as(field).isEqualTo(refreshed.get(field));
    }
    assertThat(quantity.quantityOf(stock.rowId())).isEqualTo(intervals);
    var correctedHistory = contributionHistory(fixture.orderId(), original.id());
    assertThat(correctedHistory.openAmount()).isEqualTo(1.0);
    assertThat(correctedHistory.creditedAmount()).isEqualTo(3.0);
    assertThat(correctedHistory.provisions().getFirst().quantityAmount()).isEqualTo(3.0);
    assertThat(correctedHistory.provisions().getFirst().suppliedQuality()).isEqualTo(700);
    assertThat(contributionHistory(fixture.orderId(), other.id())).isEqualTo(otherBefore);
    assertThat(correctedHistory.provisions().getFirst().id()).isEqualTo(receipt.id());
    assertThat(correctedHistory.provisions().getFirst().factChanges()).hasSize(2);
    assertThat(correctedHistory.provisions().getFirst().factChanges().getFirst())
        .isEqualTo(receipt.factChanges().getFirst());
    assertThat(materialCollection(fixture.orderId(), stock))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.inventoryEntryId()).isEqualTo(stock.rowId());
          assertThat(row.allocatedQuantity()).isEqualTo(3.0);
          assertThat(row.quantity()).isEqualTo(3.0);
          assertThat(row.quality()).isEqualTo(700.0);
          assertThat(row.delivered()).isFalse();
        });
  }

  /** Runs a public stock write only after the correction is blocked by its owning transaction. */
  private JsonNode qualityCorrectionAfterStockWait(
      Fixture fixture,
      StockFixture stock,
      UUID receipt,
      String payload,
      RequestPostProcessor actor,
      int refusalStatus,
      Callable<JsonNode> competingWrite)
      throws Exception {
    AtomicInteger blockerPid = new AtomicInteger();
    AtomicInteger waiterPid = new AtomicInteger();
    AtomicReference<Future<MockHttpServletResponse>> pending = new AtomicReference<>();
    try (var executor = Executors.newSingleThreadExecutor()) {
      JsonNode committedStock =
          transactionTemplate.execute(_ -> {
            blockerPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
            stockCommands.prepareWrites(List.of(stock.rowId()), List.of(), List.of());
            pending.set(
                executor.submit(() ->
                    observedQualityCorrection(
                        fixture, receipt, payload, actor, refusalStatus, waiterPid)));
            await()
                .atMost(Duration.ofSeconds(10))
                .until(() ->
                    pending.get().isDone() || advisoryBlockedBy(waiterPid.get(), blockerPid.get()));
            assertThat(advisoryBlockedBy(waiterPid.get(), blockerPid.get()))
                .as("the QUALITY request waits for the held complete material plan")
                .isTrue();
            return assertDoesNotThrow(competingWrite::call);
          });
      if (refusalStatus == 403) {
        mvc()
            .perform(
                get(
                        "/api/v1/orders/{order}/claims/provisions/{receipt}/quality-options",
                        fixture.orderId(),
                        receipt)
                    .with(actor))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
      }
      if (refusalStatus == 409) {
        long observedVersion =
            json.readTree(payload).path("stockVersions").get(0).path("version").asLong();
        assertThat(committedStock.get("version").asLong()).isGreaterThan(observedVersion);
        assertThat(inventory(fixture, stock).get("version").asLong())
            .isGreaterThan(observedVersion);
      }
      MockHttpServletResponse response = pending.get().get(15, TimeUnit.SECONDS);
      assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(refusalStatus);
      return committedStock;
    }
  }

  /** Captures the HTTP refusal without injecting a rollback into its observing transaction. */
  private MockHttpServletResponse observedQualityCorrection(
      Fixture fixture,
      UUID receipt,
      String payload,
      RequestPostProcessor actor,
      int refusalStatus,
      AtomicInteger pid) {
    AtomicReference<MockHttpServletResponse> response = new AtomicReference<>();
    AtomicReference<Exception> resolved = new AtomicReference<>();
    try {
      transactionTemplate.executeWithoutResult(_ -> {
        pid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
        var result =
            assertDoesNotThrow(() -> {
              return mvc()
                  .perform(
                      post(
                              "/api/v1/orders/{order}/claims/provisions/{receipt}/correction",
                              fixture.orderId(),
                              receipt)
                          .with(actor)
                          .contentType(MediaType.APPLICATION_JSON)
                          .content(payload))
                  .andReturn();
            });
        response.set(result.getResponse());
        resolved.set(result.getResolvedException());
      });
    } catch (UnexpectedRollbackException _) {
      assertThat(response.get()).isNotNull();
      String refusalBody =
          assertDoesNotThrow(() -> {
            return response.get().getContentAsString();
          });
      Exception refusal = resolved.get();
      String origin =
          refusal == null
              ? ""
              : Arrays.stream(refusal.getStackTrace())
                  .filter(frame ->
                      frame.getClassName().startsWith("de.greluc.krt.profit.basetool.backend"))
                  .map(StackTraceElement::toString)
                  .findFirst()
                  .orElse("");
      assertThat(response.get().getStatus())
          .as(
              "response=%s; exception=%s; cause=%s; origin=%s",
              refusalBody, refusal, refusal == null ? null : refusal.getCause(), origin)
          .isEqualTo(refusalStatus);
    }
    assertThat(response.get()).isNotNull();
    return response.get();
  }

  /** Reads the actual PostgreSQL advisory wait edge between the two attributed connections. */
  private boolean advisoryBlockedBy(int waiter, int blocker) {
    return waiter > 0
        && Boolean.TRUE.equals(
            jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_locks WHERE pid = ?"
                    + " AND locktype = 'advisory' AND NOT granted"
                    + " AND ? = ANY(pg_blocking_pids(pid)))",
                Boolean.class,
                waiter,
                blocker));
  }

  /** Reads complete material stock projections in stable UUID order through the public API. */
  private List<InventoryItemDto> materialInventory(Fixture fixture, StockFixture owner)
      throws Exception {
    String body =
        mvc()
            .perform(
                get("/api/v1/inventory/material/{id}", fixture.materialId())
                    .param("sort", "id,asc")
                    .with(asStockOwner(owner)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    PageResponse<InventoryItemDto> page = json.readValue(body, new TypeReference<>() {});
    assertThat(page.totalElements()).isEqualTo(page.content().size());
    return page.content();
  }

  /** Reads current per-order allocation quantities and delivered markers from the public API. */
  private List<MaterialCollectionEntryDto> materialCollection(UUID orderId, StockFixture owner)
      throws Exception {
    String body =
        mvc()
            .perform(
                get("/api/v1/orders/{id}/material-collection", orderId).with(asStockOwner(owner)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    List<MaterialCollectionEntryDto> collection = json.readValue(body, new TypeReference<>() {});
    return collection.stream()
        .sorted(Comparator.comparing(MaterialCollectionEntryDto::inventoryEntryId))
        .toList();
  }

  /** Reads only this fixture actor's committed events from the public admin audit viewer. */
  private List<AuditEventDto> inventoryEvents(StockFixture owner, AuditEventType eventType)
      throws Exception {
    String body =
        mvc()
            .perform(
                get("/api/v1/audit/INVENTORY")
                    .param("actorUserId", owner.holderId().toString())
                    .param("eventType", eventType.name())
                    .param("sort", "id,asc")
                    .with(asStockOwner(owner)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    PageResponse<AuditEventDto> page = json.readValue(body, new TypeReference<>() {});
    assertThat(page.totalElements()).isEqualTo(page.content().size());
    return page.content();
  }

  /** Applies one selected original-quality request through the ordinary public correction route. */
  private void correctQuality(
      Fixture fixture, StockFixture owner, UUID receipt, ObjectNode correction, int expectedStatus)
      throws Exception {
    mvc()
        .perform(
            post(
                    "/api/v1/orders/{order}/claims/provisions/{receipt}/correction",
                    fixture.orderId(),
                    receipt)
                .with(asStockOwner(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(correction.toString()))
        .andExpect(result ->
            assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(expectedStatus));
  }

  /** Authenticates this fixture's actual holder with the current administrator action grants. */
  private RequestPostProcessor asStockOwner(StockFixture owner) {
    return jwt()
        .jwt(token -> token.subject(owner.holderId().toString()))
        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }

  /** Commits another valid order for the same physical material and responsible unit. */
  private UUID additionalMaterialOrder(Fixture fixture) {
    return transactionTemplate.execute(_ -> {
      JobOrder original = jobOrderRepository.findById(fixture.orderId()).orElseThrow();
      JobOrder order =
          JobOrder.builder()
              .responsibleOrgUnit(original.getResponsibleOrgUnit())
              .requestingOrgUnit(original.getRequestingOrgUnit())
              .handle("quality-piece-shares")
              .status(JobOrderStatus.OPEN)
              .build();
      order.addMaterial(
          JobOrderMaterial.builder()
              .material(materialRepository.findById(fixture.materialId()).orElseThrow())
              .qualityTier(qualityTierRepository.findByCode("GOOD").orElseThrow())
              .amount(10.0)
              .build());
      return jobOrderRepository.saveAndFlush(order).getId();
    });
  }

  /** Sets one whole job-order share through the ordinary allocation endpoint. */
  private void onePieceAllocation(StockFixture owner, UUID rowId, UUID orderId, boolean existing)
      throws Exception {
    var request =
        existing
            ? patch("/api/v1/inventory/{id}/allocation", rowId)
            : post("/api/v1/inventory/{id}/allocation", rowId);
    mvc()
        .perform(
            request
                .with(
                    jwt()
                        .jwt(token -> token.subject(owner.holderId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.createObjectNode()
                        .put("field", "JOB_ORDER")
                        .put("targetId", orderId.toString())
                        .put("amount", 1.0)
                        .put("version", stockReads.findRow(rowId).orElseThrow().getVersion())
                        .toString()))
        .andExpect(status().isOk());
  }

  /** Checks the actual order share and its delivered marker in the public collection. */
  private void assertPieceCollection(UUID orderId, UUID holderId, double quality, boolean delivered)
      throws Exception {
    mvc()
        .perform(
            get("/api/v1/orders/{id}/material-collection", orderId)
                .with(
                    jwt()
                        .jwt(token -> token.subject(holderId.toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].allocatedQuantity").value(1.0))
        .andExpect(jsonPath("$[0].quality").value(quality))
        .andExpect(jsonPath("$[0].delivered").value(delivered));
  }

  /** Commits distinct stock ownership, ordinary pool membership and the order-side writer. */
  private UUID orderWriter(UUID responsible, UUID stockPool, UUID stockOwner) {
    return transactionTemplate.execute(_ -> {
      User actor = new User();
      actor.setId(UUID.randomUUID());
      actor.setUsername("stock-correction-writer-" + actor.getId());
      actor.setDisplayName("Stock correction writer");
      actor = users.saveAndFlush(actor);
      membership(actor, responsible);
      membership(actor, stockPool);
      membership(users.findById(stockOwner).orElseThrow(), stockPool);
      return actor.getId();
    });
  }

  /** Persists an ordinary direct membership without any pool-specific write grant. */
  private void membership(User user, UUID unitId) {
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(user.getId(), unitId));
    membership.setUser(user);
    membership.setKind(orgUnitRepository.findById(unitId).orElseThrow().getKind());
    membership.setJoinedAt(Instant.now());
    memberships.saveAndFlush(membership);
  }

  /** Reads the actual stock projection with its current physical and allocation state. */
  private JsonNode inventory(Fixture fixture, StockFixture stock) throws Exception {
    String body =
        mvc()
            .perform(
                get("/api/v1/inventory/material/{id}", fixture.materialId())
                    .with(
                        jwt()
                            .jwt(token -> token.subject(stock.holderId().toString()))
                            .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode content = json.readTree(body).get("content");
    assertThat(content.size()).isEqualTo(1);
    JsonNode row = content.get(0);
    assertThat(row.get("id").asString()).isEqualTo(stock.rowId().toString());
    return row;
  }

  /** Authenticates only the responsible order's contextual Logistician grant. */
  private RequestPostProcessor asOrderWriter(UUID actor, UUID responsible) {
    return jwt()
        .jwt(token -> token.subject(actor.toString()))
        .authorities(
            new SimpleGrantedAuthority("ROLE_KRT_MEMBER"),
            new SimpleGrantedAuthority("ROLE_LOGISTICIAN"),
            new OrgUnitContextualAuthority(Roles.LOGISTICIAN, responsible));
  }
}
