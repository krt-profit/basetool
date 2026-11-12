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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.joborder.api.MaterialProvisionFactChange;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import tools.jackson.databind.JsonNode;

/** Verifies retained original facts through the public correction and readable history routes. */
@SpringBootTest
@WithMockUser(roles = "ADMIN")
class MaterialProvisionFactHistoryIntegrationTest extends MaterialContributionTestSupport {

  @Test
  void correctingOnlyTheUncreditedExcessRetainsOriginalAndCorrectedFacts() throws Exception {
    Fixture fixture = seed();
    var contribution =
        materialClaimService.upsertClaim(
            fixture.orderId(), claim(fixture.materialId(), fixture.squadronAId(), 3.0));
    materialClaimService.upsertClaim(
        fixture.orderId(), claim(fixture.materialId(), fixture.squadronBId(), 7.0));
    StockFixture stock = stock(fixture, 4.0);
    provide(fixture, contribution, stock, 4.0);
    var before = contributionHistory(fixture.orderId(), contribution.id());
    var receipt = before.provisions().getFirst();
    assertThat(receipt.amount()).isEqualTo(3.0);
    assertThat(receipt.quantityAmount()).isEqualTo(4.0);
    Long rowVersion = stockReads.findRow(stock.rowId()).orElseThrow().getVersion();

    mvc()
        .perform(
            post(
                    "/api/v1/orders/{id}/claims/provisions/{credit}/correction",
                    fixture.orderId(),
                    receipt.id())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "kind", "QUANTITY",
                            "version", receipt.version(),
                            "claimVersion", before.version(),
                            "amount", 3.0,
                            "stockVersions",
                                List.of(Map.of("id", stock.rowId(), "version", rowVersion))))))
        .andExpect(status().isOk());

    var after = contributionHistory(fixture.orderId(), contribution.id());
    assertThat(after.openAmount()).isZero();
    assertThat(after.creditedAmount()).isEqualTo(3.0);
    assertThat(after.provisions().getFirst().quantityAmount()).isEqualTo(3.0);
    assertThat(stockReads.findRow(stock.rowId()).orElseThrow().getAmount()).isEqualTo(3.0);

    String body =
        mvc()
            .perform(
                get("/api/v1/orders/{id}/claims/history", fixture.orderId())
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode retained = provision(json.readTree(body), contribution.id(), receipt.id());
    JsonNode changes = retained.get("factChanges");
    assertThat(changes).as("readable receipt fact history").isNotNull();
    assertThat(changes.isArray()).isTrue();
    assertThat(changes.size()).isEqualTo(2);
    assertThat(changes.get(0).path("reason").asString()).isEqualTo("PROVISION_RECORDED");
    assertThat(changes.get(0).path("current").path("quantityAmount").asDouble()).isEqualTo(4.0);
    JsonNode correction = changes.get(1);
    assertThat(correction.path("reason").asString()).isEqualTo("FALSE_FACT_CORRECTED");
    assertThat(correction.path("previous").path("quantityAmount").asDouble()).isEqualTo(4.0);
    assertThat(correction.path("current").path("quantityAmount").asDouble()).isEqualTo(3.0);
    assertThat(correction.path("previous").path("creditedAmount").asDouble()).isEqualTo(3.0);
    assertThat(correction.path("current").path("creditedAmount").asDouble()).isEqualTo(3.0);
    assertThat(correction.path("previous").path("suppliedQuality").asInt()).isEqualTo(650);
    assertThat(correction.path("current").path("suppliedQuality").asInt()).isEqualTo(650);
    assertThat(correction.path("previous").path("claimId").asString())
        .isEqualTo(contribution.id().toString());
    assertThat(correction.path("current").path("claimId").asString())
        .isEqualTo(contribution.id().toString());
  }

  @Test
  void repeatingTheCurrentOriginalQuantityPreservesAllReceiptAndContributionFacts()
      throws Exception {
    Fixture fixture = seed();
    var contribution =
        materialClaimService.upsertClaim(
            fixture.orderId(), claim(fixture.materialId(), fixture.squadronAId(), 3.0));
    materialClaimService.upsertClaim(
        fixture.orderId(), claim(fixture.materialId(), fixture.squadronBId(), 7.0));
    StockFixture stock = stock(fixture, 3.0);
    provide(fixture, contribution, stock, 3.0);
    var before = contributionHistory(fixture.orderId(), contribution.id());
    var receipt = before.provisions().getFirst();
    assertThat(receipt.quantityAmount()).isEqualTo(3.0);
    assertThat(receipt.factChanges()).hasSize(1);

    mvc()
        .perform(
            post(
                    "/api/v1/orders/{id}/claims/provisions/{credit}/correction",
                    fixture.orderId(),
                    receipt.id())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "kind", "QUANTITY",
                            "version", receipt.version(),
                            "claimVersion", before.version(),
                            "amount", 3.0,
                            "stockVersions", List.of()))))
        .andExpect(status().isOk());

    var after = contributionHistory(fixture.orderId(), contribution.id());
    assertThat(after.version()).isEqualTo(before.version());
    assertThat(after.changes()).isEqualTo(before.changes());
    assertThat(after.provisions()).isEqualTo(before.provisions());
    assertThat(after.openAmount()).isEqualTo(before.openAmount());
    assertThat(after.creditedAmount()).isEqualTo(before.creditedAmount());
  }

  @Test
  void legacyUnknownOriginalQualityCanBeDeclaredWithoutInventingQuantityOrStock() throws Exception {
    Fixture fixture = seed();
    var contribution =
        materialClaimService.upsertClaim(
            fixture.orderId(), claim(fixture.materialId(), fixture.squadronAId(), 3.0));
    materialClaimService.upsertClaim(
        fixture.orderId(), claim(fixture.materialId(), fixture.squadronBId(), 7.0));
    StockFixture stock = stock(fixture, 3.0);
    provide(fixture, contribution, stock, 3.0);
    var recorded = contributionHistory(fixture.orderId(), contribution.id());
    UUID receiptId = recorded.provisions().getFirst().id();
    jdbc.update(
        """
        UPDATE material_provision_credit
           SET evidence = 'CREDITED_SUBSET', supplied_quality_known = false,
               fact_history = '[]'::jsonb
         WHERE id = ?
        """,
        receiptId);
    jdbc.update("DELETE FROM inventory_item WHERE id = ?", stock.rowId());
    var before = contributionHistory(fixture.orderId(), contribution.id());
    var legacy = before.provisions().getFirst();
    assertThat(legacy.evidence().name()).isEqualTo("CREDITED_SUBSET");
    assertThat(legacy.suppliedQuality()).isNull();
    assertThat(legacy.minimumSuppliedQuality()).isEqualTo(650);
    assertThat(legacy.factChanges()).isEmpty();
    assertThat(stockReads.exchangeLots(stock.holderId(), new UUID(0L, 0L), 1)).isEmpty();

    mvc()
        .perform(
            post(
                    "/api/v1/orders/{id}/claims/provisions/{credit}/correction",
                    fixture.orderId(),
                    receiptId)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "kind", "QUALITY",
                            "version", legacy.version(),
                            "claimVersion", before.version(),
                            "amount", 3.0,
                            "targetClaimId", contribution.id(),
                            "targetClaimVersion", before.version(),
                            "stockVersions", List.of(),
                            "suppliedQuality", 700))))
        .andExpect(status().isOk());

    var after = contributionHistory(fixture.orderId(), contribution.id());
    var corrected = after.provisions().getFirst();
    assertThat(after.creditedAmount()).isEqualTo(3.0);
    assertThat(after.openAmount()).isEqualTo(before.openAmount());
    assertThat(corrected.quantityAmount()).isEqualTo(3.0);
    assertThat(corrected.evidence().name()).isEqualTo("CREDITED_SUBSET");
    assertThat(corrected.suppliedQuality()).isEqualTo(700);
    assertThat(corrected.factChanges()).hasSize(1);
    var change = corrected.factChanges().getFirst();
    assertThat(change.reason()).isEqualTo(MaterialProvisionFactChange.Reason.FALSE_FACT_CORRECTED);
    assertThat(change.previous().suppliedQuality()).isNull();
    assertThat(change.previous().minimumSuppliedQuality()).isEqualTo(650);
    assertThat(change.previous().quantityEvidence().name()).isEqualTo("CREDITED_SUBSET");
    assertThat(change.previous().quantityAmount()).isEqualTo(3.0);
    assertThat(change.previous().creditedAmount()).isEqualTo(3.0);
    assertThat(change.current().suppliedQuality()).isEqualTo(700);
    assertThat(change.current().quantityEvidence().name()).isEqualTo("CREDITED_SUBSET");
    assertThat(change.current().quantityAmount()).isEqualTo(3.0);
    assertThat(change.current().creditedAmount()).isEqualTo(3.0);
    assertThat(stockReads.exchangeLots(stock.holderId(), new UUID(0L, 0L), 1)).isEmpty();
  }

  private static JsonNode provision(JsonNode history, UUID contributionId, UUID provisionId) {
    for (int i = 0; i < history.size(); i++) {
      JsonNode contribution = history.get(i);
      if (!contribution.path("id").asString().equals(contributionId.toString())) {
        continue;
      }
      JsonNode provisions = contribution.path("provisions");
      for (int j = 0; j < provisions.size(); j++) {
        JsonNode provision = provisions.get(j);
        if (provision.path("id").asString().equals(provisionId.toString())) {
          return provision;
        }
      }
    }
    throw new AssertionError("Expected the retained contribution receipt in public history");
  }
}
