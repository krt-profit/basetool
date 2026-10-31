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

package de.greluc.krt.profit.basetool.frontend.supply.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyClass;
import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.model.GameItemReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.supply.model.DonationDto;
import de.greluc.krt.profit.basetool.frontend.supply.model.MyDonationStatsDto;
import de.greluc.krt.profit.basetool.frontend.supply.model.NeedDto;
import de.greluc.krt.profit.basetool.frontend.supply.model.SupplyRefDto;
import de.greluc.krt.profit.basetool.frontend.supply.model.SupplyStateDto;
import de.greluc.krt.profit.basetool.frontend.supply.model.SupplyUnitDto;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Renders the component chips of a game item on the needs, my-donations and incoming-donations
 * lists, in the result fragments a live refresh fetches (REQ-SUP-022).
 */
@SpringBootTest
class SupplyItemChipsRenderTest {

  private static final UUID UNIT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

  private static final GameItemReferenceDto SHIELD =
      new GameItemReferenceDto(
          UUID.randomUUID(), "FR-66", "VEHICLE_ITEM", "SHIELD", 1, "A", "MILITARY");

  private static final String SHIELD_CHIPS =
      "data-testid=\"supply-item-chips\">\\s*<span class=\"chip chip--muted\""
          + " data-testid=\"item-component-type\">Schild</span>\\s*<span class=\"chip chip--data\""
          + " data-testid=\"item-size-grade\"[^>]*>S1 · A</span>\\s*<span"
          + " data-testid=\"item-class\"[^>]*class=\"chip chip--item-class-military\"[^>]*>"
          + "Militär</span>";

  @Autowired private WebApplicationContext context;

  @Autowired private SupplyFeatureState featureState;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  @BeforeEach
  void stubTheSurface() {
    featureState.evict();
    when(backendApiClient.get(eq("/api/v1/supply/state"), anyClass()))
        .thenReturn(new SupplyStateDto(true));
    when(backendApiClient.get(eq("/api/v1/supply/units"), anyTypeRef()))
        .thenReturn(
            List.of(
                new SupplyUnitDto(UNIT, "Argon", "ARG", "SPECIAL_COMMAND", true, true, true, 0L)));
  }

  private @NotNull String render(@NotNull String url) throws Exception {
    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    return mockMvc
        .perform(get(url).locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private static @NotNull NeedDto need(
      @Nullable SupplyRefDto material, @Nullable GameItemReferenceDto gameItem) {
    return new NeedDto(
        UUID.randomUUID(),
        UNIT,
        "Argon",
        "ARG",
        material,
        gameItem,
        gameItem == null ? "SCU" : "ITEM",
        null,
        4.0,
        0.0,
        4.0,
        "NORMAL",
        "OPEN",
        false,
        null,
        null,
        null,
        null,
        null,
        null,
        false,
        0L,
        Instant.now());
  }

  private static @NotNull DonationDto donation(
      @Nullable SupplyRefDto material, @Nullable GameItemReferenceDto gameItem) {
    return new DonationDto(
        UUID.randomUUID(),
        UNIT,
        "Argon",
        material,
        gameItem,
        null,
        gameItem == null ? "SCU" : "ITEM",
        1.0,
        null,
        null,
        "PLEDGED",
        null,
        "Vexx",
        true,
        false,
        null,
        false,
        false,
        0L,
        Instant.now());
  }

  private static <T> @NotNull PageResponse<T> page(@NotNull List<T> rows) {
    return new PageResponse<>(rows, 0, 10, rows.size(), 1, List.of());
  }

  /** A need for the FR-66 shield shows its type, size, grade and class; a material need none. */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void aNeedShowsTheComponentChips() throws Exception {
    when(backendApiClient.get(
            startsWith("/api/v1/supply/needs?page="), anyTypeRef(), eq(0), eq(10)))
        .thenReturn(
            page(
                List.of(
                    need(null, SHIELD),
                    need(new SupplyRefDto(UUID.randomUUID(), "Quantanium"), null))));

    String html = render("/supply/needs?fragment=results");

    assertThat(html).containsPattern(SHIELD_CHIPS).contains("Quantanium");
    assertThat(html.split("data-testid=\"supply-item-chips\"", -1)).hasSize(2);
  }

  /** The caller's own donation of the FR-66 shield shows its chips; a material donation none. */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void myDonationShowsTheComponentChips() throws Exception {
    when(backendApiClient.get(
            startsWith("/api/v1/supply/me/donations?page="), anyTypeRef(), eq(0), eq(10)))
        .thenReturn(
            page(
                List.of(
                    donation(null, SHIELD),
                    donation(new SupplyRefDto(UUID.randomUUID(), "Quantanium"), null))));
    when(backendApiClient.get(eq("/api/v1/supply/me/donations/stats"), anyClass()))
        .thenReturn(new MyDonationStatsDto(2, List.of()));

    String html = render("/supply/donations/mine?fragment=results");

    assertThat(html).containsPattern(SHIELD_CHIPS).contains("Quantanium");
    assertThat(html.split("data-testid=\"supply-item-chips\"", -1)).hasSize(2);
  }

  /** An incoming donation of the FR-66 shield shows its chips; a material donation none. */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void anIncomingDonationShowsTheComponentChips() throws Exception {
    when(backendApiClient.get(
            startsWith("/api/v1/supply/donations?orgUnitId="),
            anyTypeRef(),
            eq(UNIT),
            eq(0),
            eq(10)))
        .thenReturn(
            page(
                List.of(
                    donation(null, SHIELD),
                    donation(new SupplyRefDto(UUID.randomUUID(), "Quantanium"), null))));

    String html = render("/supply/donations/incoming?fragment=results");

    assertThat(html).containsPattern(SHIELD_CHIPS).contains("Quantanium");
    assertThat(html.split("data-testid=\"supply-item-chips\"", -1)).hasSize(2);
  }
}
