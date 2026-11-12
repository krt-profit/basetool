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

package de.greluc.krt.profit.basetool.backend.catalogue.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * The admin endpoints of the game items' obtainability end to end (REQ-P4K-048): an admin hides an
 * item and offers it again, the list filters the hidden ones, a request without a state is a {@code
 * 400}, and no other role reaches either endpoint.
 */
@SpringBootTest
@Transactional
class AdminGameItemControllerTest {

  private static final String PATH = "/api/v1/catalog/admin/game-items";

  private static final String TAG = "AdminItem" + UUID.randomUUID().toString().substring(0, 8);

  @Autowired private WebApplicationContext context;
  @Autowired private GameItemRepository gameItemRepository;
  @Autowired private UserRepository userRepository;

  private MockMvc mockMvc;
  private GameItem item;
  private UUID adminId;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    User admin = new User();
    admin.setId(UUID.randomUUID());
    admin.setUsername("item-admin-" + UUID.randomUUID());
    adminId = userRepository.saveAndFlush(admin).getId();
    item = new GameItem();
    item.setName(TAG + " Railgun");
    item.setWikiType("WeaponPersonal");
    item.setWikiSubType("Large");
    item = gameItemRepository.saveAndFlush(item);
  }

  @Test
  void anAdminHidesAnItem_findsItAmongTheHiddenOnes_andOffersItAgain() throws Exception {
    obtainability(admin(), "{\"obtainable\":false}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(item.getId().toString()))
        .andExpect(jsonPath("$.name").value(TAG + " Railgun"))
        .andExpect(jsonPath("$.unobtainableSince").isNotEmpty())
        .andExpect(jsonPath("$.retired").value(false));
    mockMvc
        .perform(get(PATH).param("query", TAG).param("unobtainableOnly", "true").with(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].id").value(item.getId().toString()));

    obtainability(admin(), "{\"obtainable\":true}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.unobtainableSince").doesNotExist());
    mockMvc
        .perform(get(PATH).param("query", TAG).param("unobtainableOnly", "true").with(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(0));
    assertThat(gameItemRepository.findById(item.getId()).orElseThrow().isUnobtainable()).isFalse();
  }

  @Test
  void aRequestWithoutAStateIsABadRequest() throws Exception {
    obtainability(admin(), "{}").andExpect(status().isBadRequest());
  }

  @Test
  void anUnknownItemIsNotFound() throws Exception {
    mockMvc
        .perform(
            put(PATH + "/" + UUID.randomUUID() + "/obtainability")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"obtainable\":false}")
                .with(admin()))
        .andExpect(status().isNotFound());
  }

  @Test
  void aLogisticianReachesNeitherEndpoint() throws Exception {
    RequestPostProcessor logistician =
        jwt()
            .jwt(builder -> builder.subject(UUID.randomUUID().toString()))
            .authorities(
                new SimpleGrantedAuthority("ROLE_KRT_MEMBER"),
                new SimpleGrantedAuthority("ROLE_LOGISTICIAN"));

    obtainability(logistician, "{\"obtainable\":false}").andExpect(status().isForbidden());
    mockMvc.perform(get(PATH).with(logistician)).andExpect(status().isForbidden());
    assertThat(gameItemRepository.findById(item.getId()).orElseThrow().isUnobtainable()).isFalse();
  }

  /**
   * Sends a change of the test item's obtainability.
   *
   * @param caller the caller
   * @param body the request body
   * @return the result
   * @throws Exception when the request fails
   */
  private ResultActions obtainability(@NotNull RequestPostProcessor caller, @NotNull String body)
      throws Exception {
    return mockMvc.perform(
        put(PATH + "/" + item.getId() + "/obtainability")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)
            .with(caller));
  }

  /**
   * The test's admin as the caller.
   *
   * @return the request post-processor
   */
  private RequestPostProcessor admin() {
    return jwt()
        .jwt(builder -> builder.subject(adminId.toString()))
        .authorities(
            new SimpleGrantedAuthority("ROLE_KRT_MEMBER"),
            new SimpleGrantedAuthority("ROLE_ADMIN"));
  }
}
