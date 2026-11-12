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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityFragments;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityProvenance;
import de.greluc.krt.profit.basetool.backend.supply.internal.DonationDto;
import de.greluc.krt.profit.basetool.backend.supply.internal.PledgeRequest;
import de.greluc.krt.profit.basetool.backend.supply.internal.ReceiveDonationRequest;
import de.greluc.krt.profit.basetool.backend.supply.internal.SupplySettingsService;
import de.greluc.krt.profit.basetool.backend.supply.internal.SupplyUnitSetting;
import de.greluc.krt.profit.basetool.backend.supply.internal.SupplyUnitSettingRepository;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Verifies that a pledged physical carrier keeps its corrected quality when received. */
@SpringBootTest
@WithMockUser(roles = "ADMIN")
class MaterialQualityDonationCarrierIntegrationTest extends MaterialContributionTestSupport {
  @Autowired private SupplySettingsService settings;
  @Autowired private SupplyUnitSettingRepository acceptingUnits;
  @Autowired private StockQuantityProvenance provenance;

  @Test
  void aWholeQualityCorrectedDonationCarrierIsReceivedAtItsCurrentQuality() throws Exception {
    boolean enabled = settings.isEnabled();
    UUID area = settings.governingAreaId().orElse(null);
    settings.update(true, area);
    try {
      Fixture fixture = seed();
      var claim =
          materialClaimService.upsertClaim(
              fixture.orderId(), claim(fixture.materialId(), fixture.squadronAId(), 3.0));
      StockFixture source = stock(fixture, 3.0);
      StockFixture other = stock(fixture, 1.0);
      UUID unit =
          Objects.requireNonNull(
              transactionTemplate.execute(_ ->
                  jobOrderRepository
                      .findById(fixture.orderId())
                      .orElseThrow()
                      .getResponsibleOrgUnit()
                      .getId()));
      SupplyUnitSetting setting = new SupplyUnitSetting();
      setting.setId(unit);
      setting.setAcceptsDonations(true);
      acceptingUnits.saveAndFlush(setting);
      provide(fixture, claim, source, 3.0);
      UUID targetLocation =
          Objects.requireNonNull(
              transactionTemplate.execute(
                  _ -> stockReads.findRow(other.rowId()).orElseThrow().getLocation().getId()));
      var original = provenance.quantityOf(source.rowId());
      String pledged =
          mvc()
              .perform(
                  post("/api/v1/supply/donations")
                      .with(asHolder(source.holderId()))
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          json.writeValueAsString(
                              new PledgeRequest(
                                  null, unit, null, null, null, 1.0, source.rowId()))))
              .andExpect(status().isCreated())
              .andReturn()
              .getResponse()
              .getContentAsString();
      DonationDto donation = json.readValue(pledged, DonationDto.class);
      var before = contributionHistory(fixture.orderId(), claim.id());
      var receipt = before.provisions().getFirst();
      var correction =
          json.createObjectNode()
              .put("kind", "QUALITY")
              .put("version", receipt.version())
              .put("claimVersion", before.version())
              .put("amount", 3.0)
              .put("targetClaimId", claim.id().toString())
              .put("targetClaimVersion", before.version())
              .put("suppliedQuality", 700);
      correction.putArray("qualityStockRowIds").add(source.rowId().toString());
      correction
          .putArray("stockVersions")
          .addObject()
          .put("id", source.rowId().toString())
          .put("version", stockReads.findRow(source.rowId()).orElseThrow().getVersion());
      mvc()
          .perform(
              post(
                      "/api/v1/orders/{order}/claims/provisions/{receipt}/correction",
                      fixture.orderId(),
                      receipt.id())
                  .with(asHolder(source.holderId()))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(correction.toString()))
          .andExpect(status().isOk());
      var corrected = contributionHistory(fixture.orderId(), claim.id());
      StockLot sourceLot =
          Objects.requireNonNull(
              transactionTemplate.execute(_ -> {
                var row = stockReads.findRow(source.rowId()).orElseThrow();
                assertThat(row.getQuality()).isEqualTo(700);
                assertThat(row.getAmount()).isEqualTo(3.0);
                assertThat(row.donationReserved()).isEqualTo(1.0);
                return new StockLot(row.getMaterial(), null, row.getLocation(), 700, false);
              }));
      StockLot destination =
          new StockLot(
              sourceLot.material(),
              null,
              locations.findById(targetLocation).orElseThrow(),
              700,
              false);
      StockLot oldQualityDestination =
          new StockLot(sourceLot.material(), null, destination.location(), 650, false);
      mvc()
          .perform(
              post("/api/v1/supply/donations/{id}/receive", donation.id())
                  .with(asHolder(source.holderId()))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      json.writeValueAsString(
                          new ReceiveDonationRequest(1.0, targetLocation, donation.version()))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("RECEIVED"));
      transactionTemplate.executeWithoutResult(_ -> {
        var grouped =
            stockReads.lotRows(
                source.holderId(), List.of(sourceLot, destination, oldQualityDestination));
        var retained = grouped.values().stream().flatMap(List::stream).toList();
        assertThat(retained.stream().mapToDouble(row -> row.getAmount()).sum()).isEqualTo(3.0);
        assertThat(grouped.get(sourceLot.key()))
            .singleElement()
            .satisfies(row -> assertThat(row.getAmount()).isEqualTo(2.0));
        assertThat(grouped.get(destination.key()))
            .singleElement()
            .satisfies(row -> {
              assertThat(row.getQuality()).isEqualTo(700);
              assertThat(row.getAmount()).isEqualTo(1.0);
              assertThat(row.getOwningOrgUnit().getId()).isEqualTo(unit);
            });
        assertThat(grouped.get(oldQualityDestination.key())).isEmpty();
        assertThat(retained.stream().mapToDouble(row -> row.donationReserved()).sum()).isZero();
        var quantity =
            retained.stream().flatMap(row -> provenance.quantityOf(row.getId()).stream()).toList();
        assertThat(StockQuantityFragments.subtract(original, quantity)).isEmpty();
        assertThat(StockQuantityFragments.subtract(quantity, original)).isEmpty();
        var untouched = stockReads.findRow(other.rowId()).orElseThrow();
        assertThat(untouched.getAmount()).isEqualTo(1.0);
        assertThat(untouched.getQuality()).isEqualTo(650);
        assertThat(untouched.getVersion()).isZero();
      });
      assertThat(contributionHistory(fixture.orderId(), claim.id())).isEqualTo(corrected);
    } finally {
      settings.update(enabled, area);
    }
  }

  private static @NotNull RequestPostProcessor asHolder(@NotNull UUID holder) {
    return jwt()
        .jwt(token -> token.subject(holder.toString()))
        .authorities(
            new SimpleGrantedAuthority("ROLE_ADMIN"),
            new SimpleGrantedAuthority("ROLE_KRT_MEMBER"));
  }
}
