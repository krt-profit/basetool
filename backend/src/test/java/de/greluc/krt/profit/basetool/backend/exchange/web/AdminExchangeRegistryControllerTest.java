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

package de.greluc.krt.profit.basetool.backend.exchange.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeSwitch;
import java.util.EnumMap;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@Transactional
class AdminExchangeRegistryControllerTest {

  private static final String CLIENTS = "/api/v1/connected-apps/admin/clients";
  private static final String SETTINGS = "/api/v1/connected-apps/admin/settings";
  private static final String BANK_SWITCH = "/api/v1/connected-apps/admin/settings/bank";
  private static final String JOB_ORDER_SWITCH = "/api/v1/connected-apps/admin/settings/job-orders";
  private static final String JOB_ORDER_WRITES_SWITCH =
      "/api/v1/connected-apps/admin/settings/job-order-writes";

  private static final String VALID =
      """
      {"clientId":"versekit","displayName":"VerseKit",
       "capabilities":["exchange.connect","exchange.blueprints.read"],
       "minClientVersion":"1.4.0","contactUrl":"https://example.org/privacy"}
      """;

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void anOfficerIsRefused() throws Exception {
    mockMvc
        .perform(get(CLIENTS).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OFFICER"))))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            post(CLIENTS)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OFFICER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID))
        .andExpect(status().isForbidden());
  }

  @Test
  void anAdminRegistersListsAndSuspendsAClient() throws Exception {
    String created =
        mockMvc
            .perform(
                post(CLIENTS).with(admin()).contentType(MediaType.APPLICATION_JSON).content(VALID))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.clientId").value("versekit"))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.capabilities[0]").value("exchange.connect"))
            .andExpect(jsonPath("$.capabilities[1]").value("exchange.blueprints.read"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = JsonPath.read(created, "$.id");
    Number version = JsonPath.read(created, "$.version");

    mockMvc
        .perform(get(CLIENTS).with(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.clientId == 'versekit')]").exists());
    mockMvc
        .perform(
            post(CLIENTS + "/" + id + "/suspend")
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + version + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUSPENDED"));
  }

  @Test
  void aDuplicateClientIdIsAConflict() throws Exception {
    mockMvc
        .perform(post(CLIENTS).with(admin()).contentType(MediaType.APPLICATION_JSON).content(VALID))
        .andExpect(status().isCreated());
    mockMvc
        .perform(post(CLIENTS).with(admin()).contentType(MediaType.APPLICATION_JSON).content(VALID))
        .andExpect(status().isConflict());
  }

  @Test
  void invalidRegistrationsAreRefused() throws Exception {
    mockMvc
        .perform(
            post(CLIENTS)
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID.replace("\"exchange.connect\",", "")))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post(CLIENTS)
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID.replace("\"versekit\"", "\"Verse_Kit\"")))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post(CLIENTS)
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID.replace("https://example.org", "http://example.org")))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post(CLIENTS)
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID.replace("exchange.blueprints.read", "exchange.everything")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void thePatchChangeWriteIsGrantedOnlyWithItsRead() throws Exception {
    mockMvc
        .perform(
            post(CLIENTS)
                .with(admin())
                .header("Accept-Language", "en")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    VALID.replace(
                        "\"exchange.connect\",", "\"exchange.connect\",\"exchange.patch.write\",")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("only together with")));
    mockMvc
        .perform(
            post(CLIENTS)
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    VALID.replace(
                        "\"exchange.connect\",",
                        "\"exchange.connect\",\"exchange.patch.read\",\"exchange.patch.write\",")))
        .andExpect(status().isCreated());
  }

  @Test
  void aFirstPartyClientIdIsRefused() throws Exception {
    for (String firstParty : new String[] {"basetool-frontend", "test-client"}) {
      mockMvc
          .perform(
              post(CLIENTS)
                  .with(admin())
                  .header("Accept-Language", "en")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(VALID.replace("\"versekit\"", "\"" + firstParty + "\"")))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.detail").value(containsString("Basetool's own software")));
    }
  }

  @Test
  void aDisplayNameWithBidiControlsOrPosingAsTheBasetoolIsRefused() throws Exception {
    mockMvc
        .perform(
            post(CLIENTS)
                .with(admin())
                .header("Accept-Language", "en")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID.replace("\"VerseKit\"", "\"Verse\\u202eKit\"")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("Latin letters")));
    mockMvc
        .perform(
            post(CLIENTS)
                .with(admin())
                .header("Accept-Language", "de")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID.replace("\"VerseKit\"", "\"Profit Basetool\"")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("nicht als das Basetool")));
  }

  @Test
  void anEditCannotRenameAClientIntoTheBasetool() throws Exception {
    String created =
        mockMvc
            .perform(
                post(CLIENTS).with(admin()).contentType(MediaType.APPLICATION_JSON).content(VALID))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = JsonPath.read(created, "$.id");
    Number version = JsonPath.read(created, "$.version");

    mockMvc
        .perform(
            put(CLIENTS + "/" + id)
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"displayName\":\"Base-Tool Sync\",\"capabilities\":[\"exchange.connect\"],"
                        + "\"version\":"
                        + version
                        + "}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void limitOverridesAboveTheirBoundsAreRefused() throws Exception {
    String withLimits = VALID.replace("\"minClientVersion\"", "%s,\"minClientVersion\"");
    mockMvc
        .perform(
            post(CLIENTS)
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(withLimits.formatted("\"requestsPerMinute\":1201")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.requestsPerMinute").exists());
    mockMvc
        .perform(
            post(CLIENTS)
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(withLimits.formatted("\"writesPerDay\":5001")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.writesPerDay").exists());
    mockMvc
        .perform(
            post(CLIENTS)
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(withLimits.formatted("\"requestsPerMinute\":1200,\"writesPerDay\":5000")))
        .andExpect(status().isCreated());
  }

  @Test
  void aStaleVersionIsAConflict() throws Exception {
    String created =
        mockMvc
            .perform(
                post(CLIENTS).with(admin()).contentType(MediaType.APPLICATION_JSON).content(VALID))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = JsonPath.read(created, "$.id");
    Number version = JsonPath.read(created, "$.version");

    mockMvc
        .perform(
            put(CLIENTS + "/" + id)
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"displayName\":\"VerseKit 2\",\"capabilities\":[\"exchange.connect\"],"
                        + "\"version\":"
                        + (version.longValue() + 5)
                        + "}"))
        .andExpect(status().isConflict());
  }

  @Test
  void theSwitchIsReadAndSet() throws Exception {
    String settings =
        mockMvc
            .perform(get("/api/v1/connected-apps/admin/settings").with(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(false))
            .andReturn()
            .getResponse()
            .getContentAsString();
    Number version = JsonPath.read(settings, "$.version");

    mockMvc
        .perform(
            put("/api/v1/connected-apps/admin/settings")
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"version\":" + version + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(true));
  }

  @Test
  void theBankSwitchStartsOffAndIsSetWithItsOwnVersion() throws Exception {
    String bank =
        mockMvc
            .perform(get(BANK_SWITCH).with(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(false))
            .andReturn()
            .getResponse()
            .getContentAsString();
    Number version = JsonPath.read(bank, "$.version");

    mockMvc
        .perform(
            put(BANK_SWITCH)
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"version\":" + version + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(true))
        .andExpect(jsonPath("$.version").value(version.longValue() + 1))
        .andExpect(jsonPath("$.updatedAt").isNotEmpty());
    mockMvc
        .perform(
            put(BANK_SWITCH)
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false,\"version\":" + version + "}"))
        .andExpect(status().isConflict());
  }

  @Test
  void onlyAnAdminReadsOrSetsTheBankSwitch() throws Exception {
    for (String role : new String[] {"ROLE_OFFICER", "ROLE_BANK_MANAGEMENT"}) {
      mockMvc
          .perform(get(BANK_SWITCH).with(jwt().authorities(new SimpleGrantedAuthority(role))))
          .andExpect(status().isForbidden());
      mockMvc
          .perform(
              put(BANK_SWITCH)
                  .with(jwt().authorities(new SimpleGrantedAuthority(role)))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"enabled\":true,\"version\":0}"))
          .andExpect(status().isForbidden());
    }
  }

  @Test
  void bothJobOrderSwitchesStartOffAndAreSetWithTheirOwnVersions() throws Exception {
    for (String path : new String[] {JOB_ORDER_SWITCH, JOB_ORDER_WRITES_SWITCH}) {
      String state =
          mockMvc
              .perform(get(path).with(admin()))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.enabled").value(false))
              .andReturn()
              .getResponse()
              .getContentAsString();
      Number version = JsonPath.read(state, "$.version");

      mockMvc
          .perform(
              put(path)
                  .with(admin())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"enabled\":true,\"version\":" + version + "}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.enabled").value(true))
          .andExpect(jsonPath("$.version").value(version.longValue() + 1))
          .andExpect(jsonPath("$.updatedAt").isNotEmpty());
      mockMvc
          .perform(
              put(path)
                  .with(admin())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"enabled\":false,\"version\":" + version + "}"))
          .andExpect(status().isConflict());
    }
    mockMvc
        .perform(get(BANK_SWITCH).with(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(false));
  }

  @Test
  void onlyAnAdminReadsOrSetsTheJobOrderSwitches() throws Exception {
    for (String path : new String[] {JOB_ORDER_SWITCH, JOB_ORDER_WRITES_SWITCH}) {
      for (String role : new String[] {"ROLE_OFFICER", "ROLE_LOGISTICIAN"}) {
        mockMvc
            .perform(get(path).with(jwt().authorities(new SimpleGrantedAuthority(role))))
            .andExpect(status().isForbidden());
        mockMvc
            .perform(
                put(path)
                    .with(jwt().authorities(new SimpleGrantedAuthority(role)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"enabled\":true,\"version\":0}"))
            .andExpect(status().isForbidden());
      }
    }
  }

  @ParameterizedTest
  @EnumSource(ExchangeSwitch.class)
  void everySwitchIsReadAndSetAloneThroughItsOwnEndpoint(@NotNull ExchangeSwitch target)
      throws Exception {
    Map<ExchangeSwitch, String> before = new EnumMap<>(ExchangeSwitch.class);
    for (ExchangeSwitch any : ExchangeSwitch.values()) {
      before.put(any, read(any));
    }
    boolean enabled = JsonPath.read(before.get(target), "$.enabled");
    Number version = JsonPath.read(before.get(target), "$.version");

    mockMvc
        .perform(
            put(path(target))
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":" + !enabled + ",\"version\":" + version + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(!enabled))
        .andExpect(jsonPath("$.version").value(version.longValue() + 1))
        .andExpect(jsonPath("$.updatedAt").isNotEmpty());

    assertThat(JsonPath.<Boolean>read(read(target), "$.enabled")).isEqualTo(!enabled);
    for (ExchangeSwitch other : ExchangeSwitch.values()) {
      if (other != target) {
        assertThat(read(other))
            .as("%s after flipping %s", other, target)
            .isEqualTo(before.get(other));
      }
    }
  }

  @Test
  void anUnknownAreaSwitchIsNotFound() throws Exception {
    mockMvc.perform(get(SETTINGS + "/garage").with(admin())).andExpect(status().isNotFound());
    mockMvc
        .perform(
            put(SETTINGS + "/garage")
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"version\":0}"))
        .andExpect(status().isNotFound());
  }

  /**
   * Reads one switch through its admin endpoint.
   *
   * @param target the switch
   * @return the response body
   * @throws Exception if the request fails
   */
  private @NotNull String read(@NotNull ExchangeSwitch target) throws Exception {
    return mockMvc
        .perform(get(path(target)).with(admin()))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /**
   * Names the admin endpoint of a switch.
   *
   * @param target the switch
   * @return its path
   */
  private static @NotNull String path(@NotNull ExchangeSwitch target) {
    return switch (target) {
      case GLOBAL -> SETTINGS;
      case BANK -> SETTINGS + "/bank";
      case JOB_ORDERS -> SETTINGS + "/job-orders";
      case JOB_ORDER_WRITES -> SETTINGS + "/job-order-writes";
    };
  }

  /**
   * An admin token.
   *
   * @return the request post-processor
   */
  private static @NotNull JwtRequestPostProcessor admin() {
    return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }
}
