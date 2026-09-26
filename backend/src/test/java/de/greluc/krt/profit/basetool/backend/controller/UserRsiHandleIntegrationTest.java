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

package de.greluc.krt.profit.basetool.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Verifies the RSI handle over the full stack against the real schema (REQ-SEC-072): the member's
 * own read and write, the case-insensitive uniqueness that never names the handle, the shape check,
 * and the read-only admin view.
 */
@SpringBootTest
@ActiveProfiles("test")
class UserRsiHandleIntegrationTest {

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private DataSource dataSource;

  @MockitoBean private JwtDecoder jwtDecoder;

  private MockMvc mockMvc;
  private JdbcTemplate jdbc;
  private final List<UUID> created = new ArrayList<>();

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    jdbc = new JdbcTemplate(dataSource);
  }

  /** Removes the accounts this test created; the test database is shared by every test class. */
  @AfterEach
  void removeCreatedAccounts() {
    created.forEach(id -> jdbc.update("DELETE FROM app_user WHERE id = ?", id));
    created.clear();
  }

  /**
   * Creates an active account.
   *
   * @return the new account's id
   */
  private UUID account() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("rsi-" + UUID.randomUUID());
    UUID id = userRepository.saveAndFlush(user).getId();
    created.add(id);
    return id;
  }

  /**
   * Returns a fresh handle in the RSI shape, unique per call.
   *
   * @return a handle no other account carries
   */
  private static String handle() {
    return "Rsi_" + UUID.randomUUID().toString().substring(0, 8);
  }

  /**
   * Authenticates as the given member.
   *
   * @param userId the member
   * @param role the realm role without prefix
   * @return the request post-processor
   */
  private static RequestPostProcessor as(UUID userId, String role) {
    return jwt()
        .jwt(j -> j.subject(userId.toString()))
        .authorities(new SimpleGrantedAuthority("ROLE_" + role));
  }

  /**
   * Reads the stored handle straight from the table.
   *
   * @param userId the member
   * @return the stored handle or {@code null}
   */
  private String storedHandle(UUID userId) {
    return jdbc.queryForObject(
        "SELECT rsi_handle FROM app_user WHERE id = ?", String.class, userId);
  }

  @Test
  void aMemberStoresReadsAndClearsTheirOwnHandle() throws Exception {
    UUID me = account();
    String handle = handle();

    mockMvc
        .perform(
            put("/api/v1/users/me/rsi-handle")
                .with(as(me, "KRT_MEMBER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rsiHandle\":\"" + handle + "\",\"version\":0}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rsiHandle").value(handle))
        .andExpect(jsonPath("$.version").value(1));

    mockMvc
        .perform(get("/api/v1/users/me/rsi-handle").with(as(me, "KRT_MEMBER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rsiHandle").value(handle));

    mockMvc
        .perform(
            put("/api/v1/users/me/rsi-handle")
                .with(as(me, "KRT_MEMBER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rsiHandle\":\"\",\"version\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rsiHandle").doesNotExist());
    assertThat(storedHandle(me)).isNull();
  }

  @Test
  void aHandleAnotherAccountCarriesInAnyCaseIsRefusedWithoutNamingIt() throws Exception {
    UUID other = account();
    UUID me = account();
    String handle = handle();
    jdbc.update("UPDATE app_user SET rsi_handle = ? WHERE id = ?", handle, other);
    String sameInOtherCase = handle.toUpperCase(Locale.ROOT);

    String body =
        mockMvc
            .perform(
                put("/api/v1/users/me/rsi-handle")
                    .with(as(me, "KRT_MEMBER"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"rsiHandle\":\"" + sameInOtherCase + "\",\"version\":0}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("DUPLICATE_ENTITY"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContainIgnoringCase(handle);
    assertThat(storedHandle(me)).isNull();
  }

  @Test
  void aHandleOutsideTheRsiShapeIsABadRequest() throws Exception {
    UUID me = account();

    mockMvc
        .perform(
            put("/api/v1/users/me/rsi-handle")
                .with(as(me, "KRT_MEMBER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rsiHandle\":\"not a handle\",\"version\":0}"))
        .andExpect(status().isBadRequest());
    assertThat(storedHandle(me)).isNull();
  }

  @Test
  void aMemberMayUseTheirOwnUsernameAsHandle() throws Exception {
    UUID me = account();
    String username = "Own_" + UUID.randomUUID().toString().substring(0, 8);
    jdbc.update("UPDATE app_user SET username = ? WHERE id = ?", username, me);

    mockMvc
        .perform(
            put("/api/v1/users/me/rsi-handle")
                .with(as(me, "KRT_MEMBER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rsiHandle\":\"" + username + "\",\"version\":0}"))
        .andExpect(status().isOk());
    assertThat(storedHandle(me)).isEqualTo(username);
  }

  @Test
  void anotherAccountsHandleCountsAsATakenName() {
    UUID other = account();
    UUID me = account();
    String handle = handle();
    jdbc.update("UPDATE app_user SET rsi_handle = ? WHERE id = ?", handle, other);

    assertThat(userRepository.existsOtherAccountWithName(handle.toLowerCase(Locale.ROOT), me))
        .isTrue();
    assertThat(userRepository.existsOtherAccountWithName(handle.toLowerCase(Locale.ROOT), other))
        .isFalse();
  }

  @Test
  void theSchemaRefusesTheSameHandleTwiceInAnyCase() {
    UUID first = account();
    UUID second = account();
    String handle = handle();
    jdbc.update("UPDATE app_user SET rsi_handle = ? WHERE id = ?", handle, first);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE app_user SET rsi_handle = ? WHERE id = ?",
                    handle.toLowerCase(Locale.ROOT),
                    second))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void theSchemaRefusesAHandleOutsideTheRsiShape() {
    UUID me = account();

    assertThatThrownBy(() -> jdbc.update("UPDATE app_user SET rsi_handle = 'a b' WHERE id = ?", me))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void anAdminReadsAMembersHandleAndNobodyElseDoes() throws Exception {
    UUID member = account();
    UUID officer = account();
    UUID admin = account();
    String handle = handle();
    jdbc.update("UPDATE app_user SET rsi_handle = ? WHERE id = ?", handle, member);

    mockMvc
        .perform(get("/api/v1/users/" + member + "/rsi-handle").with(as(officer, "OFFICER")))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(get("/api/v1/users/" + member + "/rsi-handle").with(as(admin, "ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rsiHandle").value(handle));
  }

  @Test
  void theHandleIsNeverPartOfTheSharedUserRecord() throws Exception {
    UUID member = account();
    UUID admin = account();
    jdbc.update("UPDATE app_user SET rsi_handle = ? WHERE id = ?", handle(), member);

    String body =
        mockMvc
            .perform(get("/api/v1/users/" + member).with(as(admin, "ADMIN")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain("rsiHandle").doesNotContain(storedHandle(member));
  }
}
