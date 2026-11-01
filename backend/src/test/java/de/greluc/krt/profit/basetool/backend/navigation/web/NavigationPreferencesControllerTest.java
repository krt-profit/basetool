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

package de.greluc.krt.profit.basetool.backend.navigation.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.navigation.internal.NavigationMetrics;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * Web-layer tests of {@link NavigationPreferencesController}: the authentication gate, the cap of
 * twelve, the key format, the 409 per section and the owner derived from the token (REQ-UI-032).
 */
@SpringBootTest
@Transactional
class NavigationPreferencesControllerTest {

  private static final String PREFERENCES = "/api/v1/navigation/preferences";
  private static final String FAVOURITES = PREFERENCES + "/favourites";
  private static final String APPEARANCE = PREFERENCES + "/appearance";
  private static final String HIDDEN = PREFERENCES + "/hidden";
  private static final String BOTH = PREFERENCES + "/favourites-and-hidden";
  private static final String ORDER = PREFERENCES + "/order/";
  private static final String TAB_BAR = PREFERENCES + "/tab-bar/";

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private io.micrometer.core.instrument.MeterRegistry meterRegistry;

  private MockMvc mockMvc;
  private UUID owner;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    owner = member();
  }

  private UUID member() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("nav-" + UUID.randomUUID());
    user.setRank(1);
    user.setInKeycloak(false);
    return userRepository.saveAndFlush(user).getId();
  }

  private static RequestPostProcessor as(UUID member) {
    return jwt()
        .jwt(token -> token.subject(member.toString()))
        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"));
  }

  private static String favourites(long version, int count) {
    StringBuilder keys = new StringBuilder();
    for (int i = 0; i < count; i++) {
      keys.append(i == 0 ? "" : ",").append("\"page.n").append(i).append('"');
    }
    return "{\"keys\":[" + keys + "],\"version\":" + version + "}";
  }

  @Test
  void anonymousCallersAreRefused() throws Exception {
    mockMvc.perform(get(PREFERENCES)).andExpect(status().isUnauthorized());
  }

  @Test
  void readsTheEmptyDefaults() throws Exception {
    mockMvc
        .perform(get(PREFERENCES).with(as(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.favourites.keys").isEmpty())
        .andExpect(jsonPath("$.favourites.version").value(0));
  }

  @Test
  void storesTwelveFavouritesAndReturnsTheNextVersion() throws Exception {
    mockMvc
        .perform(
            put(FAVOURITES)
                .with(as(owner))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(favourites(0, 12)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.keys.length()").value(12))
        .andExpect(jsonPath("$.version").value(1));
  }

  @Test
  void refusesAThirteenthFavourite() throws Exception {
    mockMvc
        .perform(
            put(FAVOURITES)
                .with(as(owner))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(favourites(0, 13)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void countsARefusedWriteAsRejected() throws Exception {
    double before = rejected();

    mockMvc
        .perform(
            put(FAVOURITES)
                .with(as(owner))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(favourites(0, 13)))
        .andExpect(status().isBadRequest());

    org.assertj.core.api.Assertions.assertThat(rejected()).isEqualTo(before + 1);
  }

  private double rejected() {
    return meterRegistry
        .counter(
            NavigationMetrics.PREFERENCE_WRITES,
            NavigationMetrics.TAG_SECTION,
            "favourites",
            MetricNames.TAG_OUTCOME,
            "rejected")
        .count();
  }

  @Test
  void refusesAMalformedKey() throws Exception {
    mockMvc
        .perform(
            put(FAVOURITES)
                .with(as(owner))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"keys\":[\"/missions\"],\"version\":0}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void refusesAMissingVersion() throws Exception {
    mockMvc
        .perform(
            put(FAVOURITES)
                .with(as(owner))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"keys\":[\"missions.list\"]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void answers409ForAStaleVersion() throws Exception {
    mockMvc
        .perform(
            put(FAVOURITES)
                .with(as(owner))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(favourites(0, 1)))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            put(FAVOURITES)
                .with(as(owner))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(favourites(0, 2)))
        .andExpect(status().isConflict());
  }

  @Test
  void aMembersFavouritesAreInvisibleToAnotherMember() throws Exception {
    mockMvc
        .perform(
            put(FAVOURITES)
                .with(as(owner))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(favourites(0, 3)))
        .andExpect(status().isOk());

    mockMvc
        .perform(get(PREFERENCES).with(as(member())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.favourites.keys").isEmpty());
  }

  @Test
  void pruneDeletesTheNamedEntries() throws Exception {
    mockMvc
        .perform(
            put(FAVOURITES)
                .with(as(owner))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"keys\":[\"missions.list\",\"orders.demand\"],\"version\":0}"))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            post(PREFERENCES + "/prune")
                .with(as(owner))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"keys\":[\"orders.demand\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.favourites.keys[0]").value("missions.list"))
        .andExpect(jsonPath("$.favourites.keys.length()").value(1))
        .andExpect(jsonPath("$.favourites.version").value(2));
  }

  private org.springframework.test.web.servlet.ResultActions putAppearance(String body)
      throws Exception {
    return mockMvc.perform(
        put(APPEARANCE)
            .with(as(owner))
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  @Test
  void readsTheDrawerAsTheDefaultAppearance() throws Exception {
    mockMvc
        .perform(get(PREFERENCES).with(as(owner)))
        .andExpect(jsonPath("$.appearance.desktopMode").value("DRAWER"))
        .andExpect(jsonPath("$.appearance.version").value(0));
  }

  @Test
  void storesTheDesktopModeAndReturnsTheNextVersion() throws Exception {
    putAppearance("{\"desktopMode\":\"RAIL\",\"version\":0}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.desktopMode").value("RAIL"))
        .andExpect(jsonPath("$.version").value(1));
  }

  @Test
  void refusesAnUnknownDesktopMode() throws Exception {
    putAppearance("{\"desktopMode\":\"TOP\",\"version\":0}").andExpect(status().isBadRequest());
  }

  @Test
  void refusesAMissingAppearanceVersion() throws Exception {
    putAppearance("{\"desktopMode\":\"RAIL\"}").andExpect(status().isBadRequest());
  }

  @Test
  void answers409ForAStaleAppearanceVersion() throws Exception {
    putAppearance("{\"desktopMode\":\"RAIL\",\"version\":0}").andExpect(status().isOk());

    putAppearance("{\"desktopMode\":\"PINNED\",\"version\":0}").andExpect(status().isConflict());
  }

  @Test
  void anAppearanceWriteLeavesTheFavouritesVersionAlone() throws Exception {
    putAppearance("{\"desktopMode\":\"PINNED\",\"version\":0}").andExpect(status().isOk());

    mockMvc
        .perform(get(PREFERENCES).with(as(owner)))
        .andExpect(jsonPath("$.favourites.version").value(0))
        .andExpect(jsonPath("$.appearance.desktopMode").value("PINNED"));
  }

  private void putJson(String path, String body, int status) throws Exception {
    mockMvc
        .perform(
            put(path)
                .with(as(owner))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().is(status));
  }

  @Test
  void storesAnOrderScopeAndReadsItBackPerMode() throws Exception {
    putJson(ORDER + "member/groups", "{\"keys\":[\"trade\",\"bank\"],\"version\":0}", 200);

    mockMvc
        .perform(get(PREFERENCES).with(as(owner)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.order.member.scopes.groups[0]").value("trade"))
        .andExpect(jsonPath("$.order.member.scopes.groups[1]").value("bank"))
        .andExpect(jsonPath("$.order.member.version").value(1))
        .andExpect(jsonPath("$.order.admin.version").value(0));
  }

  @Test
  void answers409ForAStaleOrderVersionAndLeavesTheOtherModeAlone() throws Exception {
    putJson(ORDER + "member/groups", "{\"keys\":[\"trade\"],\"version\":0}", 200);

    putJson(ORDER + "member/groups", "{\"keys\":[\"bank\"],\"version\":0}", 409);
    putJson(ORDER + "admin/groups", "{\"keys\":[\"admin-members\"],\"version\":0}", 200);
  }

  @Test
  void refusesAMalformedOrderWrite() throws Exception {
    putJson(ORDER + "member/groups", "{\"keys\":[\"Trade\"],\"version\":0}", 400);
    putJson(ORDER + "member/groups", "{\"keys\":[\"trade\",\"trade\"],\"version\":0}", 400);
    putJson(ORDER + "member/groups", "{\"keys\":[\"trade\"]}", 400);
    putJson(ORDER + "guest/groups", "{\"keys\":[\"trade\"],\"version\":0}", 400);
  }

  @Test
  void resetsTheOrderOfAMode() throws Exception {
    putJson(ORDER + "member/groups", "{\"keys\":[\"trade\",\"bank\"],\"version\":0}", 200);

    mockMvc
        .perform(
            post(ORDER + "member/reset")
                .with(as(owner))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.scopes").isEmpty())
        .andExpect(jsonPath("$.version").value(2));
  }

  @Test
  void aMembersOrderIsInvisibleToAnotherMember() throws Exception {
    putJson(ORDER + "member/groups", "{\"keys\":[\"trade\",\"bank\"],\"version\":0}", 200);

    mockMvc
        .perform(get(PREFERENCES).with(as(member())))
        .andExpect(jsonPath("$.order.member.scopes").isEmpty());
  }

  @Test
  void storesHiddenPagesAndReadsThemBack() throws Exception {
    mockMvc
        .perform(
            put(HIDDEN)
                .with(as(owner))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"keys\":[\"bank.dashboard\"],\"version\":0}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.keys[0]").value("bank.dashboard"))
        .andExpect(jsonPath("$.version").value(1));

    mockMvc
        .perform(get(PREFERENCES).with(as(owner)))
        .andExpect(jsonPath("$.hidden.keys[0]").value("bank.dashboard"))
        .andExpect(jsonPath("$.hidden.version").value(1))
        .andExpect(jsonPath("$.favourites.version").value(0));
  }

  @Test
  void answers409ForAStaleHiddenVersionAnd400ForAMalformedKey() throws Exception {
    putJson(HIDDEN, "{\"keys\":[\"bank.dashboard\"],\"version\":0}", 200);
    putJson(HIDDEN, "{\"keys\":[\"orders.list\"],\"version\":0}", 409);
    putJson(HIDDEN, "{\"keys\":[\"/orders\"],\"version\":1}", 400);
  }

  @Test
  void theCombinedWriteChecksBothVersionsAndRefusesAKeyInBothLists() throws Exception {
    putJson(FAVOURITES, "{\"keys\":[\"missions.list\"],\"version\":0}", 200);

    putJson(
        BOTH,
        "{\"favourites\":{\"keys\":[\"orders.list\"],\"version\":1},"
            + "\"hidden\":{\"keys\":[\"orders.list\"],\"version\":0}}",
        400);
    putJson(
        BOTH,
        "{\"favourites\":{\"keys\":[\"orders.list\"],\"version\":0},"
            + "\"hidden\":{\"keys\":[\"bank.dashboard\"],\"version\":0}}",
        409);
    putJson(
        BOTH,
        "{\"favourites\":{\"keys\":[\"orders.list\"],\"version\":1},"
            + "\"hidden\":{\"keys\":[\"bank.dashboard\"],\"version\":0}}",
        200);
  }

  @Test
  void storesATabBarPerModeAndReadsItBack() throws Exception {
    putJson(
        TAB_BAR + "member",
        "{\"slots\":[\"orders.list\",null,\"missions.list\"],\"version\":0}",
        200);

    mockMvc
        .perform(get(PREFERENCES).with(as(owner)))
        .andExpect(jsonPath("$.tabBars.member.slots[0]").value("orders.list"))
        .andExpect(jsonPath("$.tabBars.member.slots[1]").isEmpty())
        .andExpect(jsonPath("$.tabBars.member.version").value(1))
        .andExpect(jsonPath("$.tabBars.admin.version").value(0))
        .andExpect(jsonPath("$.favourites.version").value(0));
  }

  @Test
  void theTabBarVersionIsPerMode() throws Exception {
    putJson(TAB_BAR + "member", "{\"slots\":[\"a.one\",null,null],\"version\":0}", 200);

    putJson(TAB_BAR + "admin", "{\"slots\":[\"a.one\",null,null],\"version\":0}", 200);
    putJson(TAB_BAR + "member", "{\"slots\":[\"b.two\",null,null],\"version\":0}", 409);
  }

  @Test
  void refusesAnUnknownModeAWrongSlotCountAndARepeatedKey() throws Exception {
    putJson(TAB_BAR + "tablet", "{\"slots\":[null,null,null],\"version\":0}", 400);
    putJson(TAB_BAR + "member", "{\"slots\":[\"a.one\",null],\"version\":0}", 400);
    putJson(TAB_BAR + "member", "{\"slots\":[\"a.one\",\"a.one\",null],\"version\":0}", 400);
    putJson(TAB_BAR + "member", "{\"slots\":[\"/orders\",null,null],\"version\":0}", 400);
  }

  @Test
  void countsARefusedTabBarWriteAsRejectedInItsOwnSection() throws Exception {
    double before = rejected("tabbar");

    putJson(TAB_BAR + "admin", "{\"slots\":[\"a.one\"],\"version\":0}", 400);

    org.assertj.core.api.Assertions.assertThat(rejected("tabbar")).isEqualTo(before + 1);
  }

  private double rejected(String section) {
    return meterRegistry
        .counter(
            NavigationMetrics.PREFERENCE_WRITES,
            NavigationMetrics.TAG_SECTION,
            section,
            MetricNames.TAG_OUTCOME,
            "rejected")
        .count();
  }
}
