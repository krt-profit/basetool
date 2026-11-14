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

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryRowVersionDto;
import de.greluc.krt.profit.basetool.backend.patchchange.api.PatchKind;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.HomeLocationPhases;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.PatchChangeOptions;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.PatchChangeTrigger;
import de.greluc.krt.profit.basetool.backend.patchchange.internal.TriggerPatchChangeRequest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * A provision correction restores a historic fact, so it may add quantity in place to a stock row
 * created before the live time even while the holder's relocation is pending, the one exception to
 * the no-mixing rule (REQ-PATCH-004, REQ-INV-026, ADR-0381).
 *
 * <p>Its live time lies in 2000, before every other test's dated rows; the patch change asks for a
 * home location and wipes nothing, and is removed afterwards.
 */
@SpringBootTest
@WithMockUser(roles = "ADMIN")
class MaterialProvisionCorrectionDuringRelocationIntegrationTest
    extends MaterialContributionTestSupport {

  private static final Instant LIVE_AT = Instant.parse("2000-01-01T00:00:00Z");
  private static final Instant BEFORE = Instant.parse("1999-06-01T00:00:00Z");

  @Autowired private PatchChangeTrigger trigger;
  @Autowired private HomeLocationPhases phases;

  @AfterEach
  void removeThePatchChange() {
    jdbc.update("DELETE FROM patch_change");
  }

  @Test
  void anUpwardCorrectionAddsToAPrePatchRowWhileItsRelocationIsPending() throws Exception {
    Fixture fixture = seed();
    var claim =
        materialClaimService.upsertClaim(
            fixture.orderId(), claim(fixture.materialId(), fixture.squadronAId(), 4.0));
    StockFixture stock = stock(fixture, 3.0);
    provide(fixture, claim, stock, 3.0);
    jdbc.update(
        "UPDATE app_user SET approval_status = 'ACTIVE', created_at = ? WHERE id = ?",
        Timestamp.from(BEFORE),
        stock.holderId());
    jdbc.update(
        "UPDATE inventory_item SET created_at = ? WHERE id = ?",
        Timestamp.from(BEFORE),
        stock.rowId());
    openHomeLocationPhase();
    // ServletTestExecutionListener binds one mock request for the whole method; its RequestMemo
    // still holds the "no open phase" read while the stock was booked.
    RequestContextHolder.resetRequestAttributes();
    assertThat(phases.mustChoose(stock.holderId()))
        .as("the holder's relocation is pending in the open phase")
        .isTrue();
    var before = contributionHistory(fixture.orderId(), claim.id());
    var receipt = before.provisions().getFirst();
    Long rowVersion = stockReads.findRow(stock.rowId()).orElseThrow().getVersion();
    var upward =
        new MaterialProvisionCorrectionDto(
            MaterialProvisionCorrectionDto.Kind.QUANTITY,
            receipt.version(),
            before.version(),
            4.0,
            null,
            null,
            List.of(new InventoryRowVersionDto(stock.rowId(), rowVersion)),
            stock.rowId(),
            null,
            List.of());

    mvc()
        .perform(
            post(
                    "/api/v1/orders/{id}/claims/provisions/{credit}/correction",
                    fixture.orderId(),
                    receipt.id())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(upward)))
        .andExpect(status().isOk());

    var row = stockReads.findRow(stock.rowId()).orElseThrow();
    assertThat(row.getAmount()).as("the quantity lands on the pre-patch row itself").isEqualTo(4.0);
    assertThat(
            jdbc.queryForObject(
                    "SELECT created_at FROM inventory_item WHERE id = ?",
                    Timestamp.class,
                    stock.rowId())
                .toInstant())
        .isEqualTo(BEFORE);
    assertThat(contributionHistory(fixture.orderId(), claim.id()).creditedAmount()).isEqualTo(4.0);
  }

  /** Triggers, as an admin, a rebuild that wipes nothing and asks for a home location. */
  private void openHomeLocationPhase() {
    Authentication previous = SecurityContextHolder.getContext().getAuthentication();
    User adminUser = new User();
    adminUser.setId(UUID.randomUUID());
    adminUser.setUsername("relocation-admin-" + UUID.randomUUID());
    UUID adminId = users.saveAndFlush(adminUser).getId();
    Jwt admin =
        Jwt.withTokenValue("t")
            .header("alg", "none")
            .subject(adminId.toString())
            .claim("azp", "basetool-frontend")
            .issuedAt(Instant.now())
            .build();
    SecurityContextHolder.getContext()
        .setAuthentication(
            new JwtAuthenticationToken(admin, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    try {
      trigger.trigger(
          new TriggerPatchChangeRequest(
              "0.1",
              PatchKind.REBUILD,
              LIVE_AT,
              new PatchChangeOptions(Set.of(), false, false, true),
              "0.1"));
    } finally {
      SecurityContextHolder.getContext().setAuthentication(previous);
    }
  }
}
