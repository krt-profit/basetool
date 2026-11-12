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

import de.greluc.krt.profit.basetool.backend.joborder.api.ClaimDto;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;

/** Verifies observed-version no-op saves preserve contribution and personal association facts. */
@SpringBootTest
@WithMockUser(roles = "ADMIN")
class MaterialClaimNoOpIntegrationTest extends MaterialContributionTestSupport {
  @Test
  void unchangedAmountKeepsHistoryVersionAndOriginalPersonalAssociation() throws Exception {
    Fixture fixture = seed();
    UUID creator = stock(fixture, 0.001).holderId();
    UUID observer = stock(fixture, 0.001).holderId();
    var initial =
        mvc()
            .perform(
                post("/api/v1/orders/{id}/claims", fixture.orderId())
                    .with(
                        jwt()
                            .jwt(token -> token.subject(creator.toString()))
                            .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            claim(fixture.materialId(), fixture.squadronAId(), 4.0))))
            .andExpect(status().isCreated())
            .andReturn();
    ClaimDto created = json.readValue(initial.getResponse().getContentAsString(), ClaimDto.class);
    var before = contributionHistory(fixture.orderId(), created.id());
    var same =
        new CreateClaimDto(
            fixture.materialId(), "GOOD", fixture.squadronAId(), 4.0, created.version());

    var replay =
        mvc()
            .perform(
                post("/api/v1/orders/{id}/claims", fixture.orderId())
                    .with(
                        jwt()
                            .jwt(token -> token.subject(observer.toString()))
                            .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(same)))
            .andExpect(status().isCreated())
            .andReturn();
    ClaimDto unchanged = json.readValue(replay.getResponse().getContentAsString(), ClaimDto.class);
    assertThat(unchanged.id()).isEqualTo(created.id());
    assertThat(unchanged.version()).isEqualTo(created.version());
    assertThat(unchanged.claimedByUserId()).isEqualTo(creator);
    assertThat(contributionHistory(fixture.orderId(), created.id())).isEqualTo(before);
    var stale =
        new CreateClaimDto(
            fixture.materialId(), "GOOD", fixture.squadronAId(), 4.0, created.version() + 1);
    mvc()
        .perform(
            post("/api/v1/orders/{id}/claims", fixture.orderId())
                .with(
                    jwt()
                        .jwt(token -> token.subject(observer.toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(stale)))
        .andExpect(status().isConflict());
    assertThat(contributionHistory(fixture.orderId(), created.id())).isEqualTo(before);
  }
}
