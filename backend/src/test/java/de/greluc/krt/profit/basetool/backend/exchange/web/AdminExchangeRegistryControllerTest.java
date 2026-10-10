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

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

  private static final String CLIENTS = "/api/v1/admin/exchange-clients";

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
  void aFirstPartyClientIdIsRefused() throws Exception {
    for (String firstParty :
        new String[] {"basetool-frontend", "basetool-android", "test-client"}) {
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
            .perform(get("/api/v1/admin/exchange-settings").with(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(false))
            .andReturn()
            .getResponse()
            .getContentAsString();
    Number version = JsonPath.read(settings, "$.version");

    mockMvc
        .perform(
            put("/api/v1/admin/exchange-settings")
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"version\":" + version + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(true));
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
