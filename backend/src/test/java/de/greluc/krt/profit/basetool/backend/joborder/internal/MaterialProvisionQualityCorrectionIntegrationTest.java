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

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;

/** Verifies explicit original quality corrections independently of retained physical stock. */
@SpringBootTest
@WithMockUser(roles = "ADMIN")
class MaterialProvisionQualityCorrectionIntegrationTest extends MaterialContributionTestSupport {
  @Test
  void historicalQualityCorrectionKeepsExistingAttributionAfterTheOriginalStockWasConsumed()
      throws Exception {
    Fixture fixture = seed();
    var original =
        materialClaimService.upsertClaim(
            fixture.orderId(), claim(fixture.materialId(), fixture.squadronAId(), 4.0));
    materialClaimService.upsertClaim(
        fixture.orderId(), claim(fixture.materialId(), fixture.squadronBId(), 6.0));
    StockFixture source = stock(fixture, 3.0);
    provide(fixture, original, source, 3.0);
    var handover =
        new JobOrderHandoverCreateDto(
            Instant.now(),
            "Handover recipient",
            null,
            List.of(new JobOrderHandoverItemCreateDto(source.rowId(), 3.0, null, null)));
    mvc()
        .perform(
            post("/api/v1/orders/{id}/handovers", fixture.orderId())
                .with(
                    jwt()
                        .jwt(token -> token.subject(source.holderId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(handover)))
        .andExpect(status().isCreated());
    assertThat(stockReads.findRow(source.rowId())).isEmpty();
    var before = contributionHistory(fixture.orderId(), original.id());
    var receipt = before.provisions().getFirst();
    assertThat(receipt.suppliedQuality()).isEqualTo(650);
    var correction =
        json.createObjectNode()
            .put("kind", "QUALITY")
            .put("version", receipt.version())
            .put("claimVersion", before.version())
            .put("amount", 3.0)
            .put("targetClaimId", original.id().toString())
            .put("targetClaimVersion", before.version())
            .put("suppliedQuality", 700);
    correction.putArray("stockVersions");

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

    var after = contributionHistory(fixture.orderId(), original.id());
    assertThat(after.openAmount()).isEqualTo(before.openAmount());
    assertThat(after.creditedAmount()).isEqualTo(3.0);
    var corrected = after.provisions().getFirst();
    assertThat(corrected.id()).isEqualTo(receipt.id());
    assertThat(corrected.amount()).isEqualTo(3.0);
    assertThat(corrected.quantityAmount()).isEqualTo(3.0);
    assertThat(corrected.suppliedQuality()).isEqualTo(700);
    assertThat(corrected.factChanges()).hasSize(2);
    assertThat(corrected.factChanges().getFirst()).isEqualTo(receipt.factChanges().getFirst());
    var change = corrected.factChanges().getLast();
    assertThat(change.previous()).isNotNull();
    assertThat(change.previous().suppliedQuality()).isEqualTo(650);
    assertThat(change.current().suppliedQuality()).isEqualTo(700);
    assertThat(change.previous().claimId()).isEqualTo(original.id());
    assertThat(change.current().claimId()).isEqualTo(original.id());
    assertThat(change.previous().creditedAmount()).isEqualTo(3.0);
    assertThat(change.current().creditedAmount()).isEqualTo(3.0);
    assertThat(stockReads.findRow(source.rowId())).isEmpty();
  }

  @Test
  void correctedQualityOptionsKeepTheCurrentZeroOpenAttributionWithoutUsingTodaysPromise()
      throws Exception {
    Fixture fixture = seed();
    var original =
        materialClaimService.upsertClaim(
            fixture.orderId(), claim(fixture.materialId(), fixture.squadronAId(), 3.0));
    materialClaimService.upsertClaim(
        fixture.orderId(), claim(fixture.materialId(), fixture.squadronBId(), 7.0));
    StockFixture source = stock(fixture, 3.0);
    provide(fixture, original, source, 3.0);
    var before = contributionHistory(fixture.orderId(), original.id());
    assertThat(before.openAmount()).isZero();
    var receipt = before.provisions().getFirst();
    var permitted = qualityTargets(fixture, receipt.id(), 700);
    assertThat(permitted)
        .anySatisfy(target -> {
          assertThat(target.claimId()).isEqualTo(original.id());
          assertThat(target.claimVersion()).isEqualTo(before.version());
          assertThat(target.openAmount()).isZero();
          assertThat(target.maxAmount()).isEqualTo(3.0);
        });
    assertThat(qualityTargets(fixture, receipt.id(), 500)).isEmpty();
    assertThat(contributionHistory(fixture.orderId(), original.id())).isEqualTo(before);
  }

  private List<de.greluc.krt.profit.basetool.backend.joborder.api.MaterialAttributionTargetDto>
      qualityTargets(Fixture fixture, java.util.UUID receiptId, int quality) throws Exception {
    String body =
        mvc()
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/orders/{order}/claims/provisions/{receipt}/attribution-options",
                        fixture.orderId(),
                        receiptId)
                    .param("suppliedQuality", Integer.toString(quality))
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readValue(
        body,
        new tools.jackson.core.type.TypeReference<
            List<
                de.greluc.krt.profit.basetool.backend.joborder.api
                    .MaterialAttributionTargetDto>>() {});
  }

  @Test
  void correctingSelectedOriginalQualityChangesOnlyItsKnownPartOfAMergedCurrentRow()
      throws Exception {
    Fixture fixture = seed();
    var original =
        materialClaimService.upsertClaim(
            fixture.orderId(), claim(fixture.materialId(), fixture.squadronAId(), 3.0));
    materialClaimService.upsertClaim(
        fixture.orderId(), claim(fixture.materialId(), fixture.squadronBId(), 7.0));
    StockFixture source = stock(fixture, 3.0);
    provide(fixture, original, source, 3.0);
    java.util.UUID merged =
        transactionTemplate.execute(_ -> {
          var row = stockReads.findRow(source.rowId()).orElseThrow();
          return stockCommands.bookIntoPool(
              source.holderId(),
              row.getOwningOrgUnit(),
              new de.greluc.krt.profit.basetool.backend.inventory.api.StockLot(
                  row.getMaterial(), null, row.getLocation(), 650, false),
              4.0,
              de.greluc.krt.profit.basetool.backend.inventory.api.StockBookingReason.PURCHASE);
        });
    assertThat(merged).isNotEqualTo(source.rowId());
    assertThat(stockReads.findRow(source.rowId())).isEmpty();
    var allocation =
        json.createObjectNode()
            .put("field", "JOB_ORDER")
            .put("targetId", fixture.orderId().toString())
            .put("amount", 7.0)
            .put("version", stockReads.findRow(merged).orElseThrow().getVersion());
    mvc()
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                    "/api/v1/inventory/{id}/allocation", merged)
                .with(
                    jwt()
                        .jwt(token -> token.subject(source.holderId().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(allocation.toString()))
        .andExpect(status().isOk());
    var before = contributionHistory(fixture.orderId(), original.id());
    var receipt = before.provisions().getFirst();
    String optionsBody =
        mvc()
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/orders/{order}/claims/provisions/{receipt}/quality-options",
                        fixture.orderId(),
                        receipt.id())
                    .with(
                        jwt()
                            .jwt(token -> token.subject(source.holderId().toString()))
                            .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var choices = json.readTree(optionsBody);
    assertThat(choices.size()).isEqualTo(1);
    assertThat(choices.get(0).path("id").asString()).isEqualTo(merged.toString());
    assertThat(choices.get(0).path("amount").asDouble()).isEqualTo(7.0);
    assertThat(choices.get(0).path("affectedAmount").asDouble()).isEqualTo(3.0);
    assertThat(choices.get(0).path("quality").asInt()).isEqualTo(650);
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
    String body =
        mvc()
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/orders/{order}/material-collection", fixture.orderId())
                    .with(
                        jwt()
                            .jwt(token -> token.subject(source.holderId().toString()))
                            .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    List<de.greluc.krt.profit.basetool.backend.inventory.api.MaterialCollectionEntryDto> rows =
        json.readValue(
            body,
            new tools.jackson.core.type.TypeReference<
                List<
                    de.greluc.krt.profit.basetool.backend.inventory.api
                        .MaterialCollectionEntryDto>>() {});
    assertThat(rows)
        .hasSize(2)
        .anySatisfy(row -> {
          assertThat(row.quality()).isEqualTo(650.0);
          assertThat(row.quantity()).isEqualTo(4.0);
          assertThat(row.allocatedQuantity()).isEqualTo(4.0);
        })
        .anySatisfy(row -> {
          assertThat(row.quality()).isEqualTo(700.0);
          assertThat(row.quantity()).isEqualTo(3.0);
          assertThat(row.allocatedQuantity()).isEqualTo(3.0);
        });
    String connectedBody =
        mvc()
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/orders/{order}/claims/provisions/{receipt}/correction-options",
                        fixture.orderId(),
                        receipt.id())
                    .with(
                        jwt()
                            .jwt(token -> token.subject(source.holderId().toString()))
                            .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    List<de.greluc.krt.profit.basetool.backend.inventory.api.InventoryQuantityCorrectionOptionDto>
        connected =
            json.readValue(
                connectedBody,
                new tools.jackson.core.type.TypeReference<
                    List<
                        de.greluc.krt.profit.basetool.backend.inventory.api
                            .InventoryQuantityCorrectionOptionDto>>() {});
    assertThat(connected)
        .singleElement()
        .satisfies(row -> {
          assertThat(row.quality()).isEqualTo(700);
          assertThat(row.amount()).isEqualTo(3.0);
        });
    var after = contributionHistory(fixture.orderId(), original.id());
    assertThat(after.creditedAmount()).isEqualTo(3.0);
    assertThat(after.provisions().getFirst().quantityAmount()).isEqualTo(3.0);
    assertThat(after.provisions().getFirst().suppliedQuality()).isEqualTo(700);
    assertThat(after.provisions().getFirst().factChanges()).hasSize(2);
  }
}
