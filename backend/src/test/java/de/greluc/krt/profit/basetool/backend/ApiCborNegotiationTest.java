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

package de.greluc.krt.profit.basetool.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.cbor.CBORMapper;

/**
 * The second representation of the API, and the four things that must not move with it (ADR-0161
 * §8.5).
 *
 * <p>Nothing in this repository registers a CBOR converter by hand. Spring Framework 7 detects
 * {@code tools.jackson.dataformat.cbor.CBORMapper} on the classpath and contributes one, which
 * makes "add a dependency" the entire implementation — and makes a test the only place the
 * behaviour is actually stated. What follows is that statement: the same objects, the same errors,
 * a cache key that distinguishes the two, and no client that did not ask for it noticing at all.
 */
@SpringBootTest
@ActiveProfiles("test")
@org.springframework.security.test.context.support.WithMockUser
class ApiCborNegotiationTest {

  /** Reads the binary body back into the same tree the JSON body decodes to. */
  private static final CBORMapper CBOR = CBORMapper.builder().build();

  @Autowired private WebApplicationContext context;

  @Autowired
  private de.greluc.krt.profit.basetool.backend.filter.ApiCacheControlFilter apiCacheControlFilter;

  @MockitoBean private JwtDecoder jwtDecoder;

  private MockMvc mockMvc;

  @BeforeEach
  void setup() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(apiCacheControlFilter)
            .apply(
                org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                    .springSecurity())
            .build();
  }

  @Test
  @DisplayName("a caller asking for CBOR gets CBOR, and it decodes to the same document")
  void cborIsServedAndDecodesToTheSameDocument() throws Exception {
    byte[] cbor =
        mockMvc
            .perform(get("/api/v1/job-types").accept(MediaType.APPLICATION_CBOR))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_CBOR_VALUE))
            .andReturn()
            .getResponse()
            .getContentAsByteArray();

    String json =
        mockMvc
            .perform(get("/api/v1/job-types").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode fromCbor = CBOR.readTree(cbor);
    JsonNode fromJson = tools.jackson.databind.json.JsonMapper.builder().build().readTree(json);

    JsonNode rows = fromJson.has("content") ? fromJson.get("content") : fromJson;
    if (rows.isEmpty()) {
      org.junit.jupiter.api.Assumptions.abort(
          "no job types seeded, so this comparison would be vacuous — the type-level guarantee is"
              + " CborJsonFidelityTest, which needs no data");
    }
    assertThat(fromCbor).isEqualTo(fromJson);
  }

  @Test
  @DisplayName("a caller that sends NO Accept header at all still gets JSON")
  void callersWithoutAnAcceptHeaderGetJson() throws Exception {
    mockMvc
        .perform(get("/api/v1/job-types"))
        .andExpect(status().isOk())
        .andExpect(
            header()
                .string(
                    HttpHeaders.CONTENT_TYPE,
                    org.hamcrest.Matchers.containsString(MediaType.APPLICATION_JSON_VALUE)));
  }

  @Test
  @DisplayName("a CBOR request body is refused, because only responses negotiate")
  void cborRequestBodiesAreRefused() throws Exception {
    SimpleGrantedAuthority member = new SimpleGrantedAuthority("ROLE_KRT_MEMBER");

    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/missions")
                .with(jwt().authorities(member))
                .contentType(MediaType.APPLICATION_CBOR)
                .content(new byte[] {(byte) 0xa0}))
        .andExpect(status().isUnsupportedMediaType());
  }

  @Test
  @DisplayName("a caller that does not ask for CBOR is completely unaffected")
  void jsonCallersAreUnaffected() throws Exception {
    mockMvc
        .perform(get("/api/v1/job-types").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(
            header()
                .string(
                    HttpHeaders.CONTENT_TYPE,
                    org.hamcrest.Matchers.containsString(MediaType.APPLICATION_JSON_VALUE)));
  }

  @Test
  @DisplayName("an RFC 7807 problem stays JSON even when the caller asked for CBOR")
  void problemsStayJsonUnderACborAccept() throws Exception {
    SimpleGrantedAuthority member = new SimpleGrantedAuthority("ROLE_KRT_MEMBER");

    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/missions")
                .with(jwt().authorities(member))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .accept(MediaType.APPLICATION_CBOR))
        .andExpect(status().isBadRequest())
        .andExpect(
            header()
                .string(
                    HttpHeaders.CONTENT_TYPE,
                    org.hamcrest.Matchers.containsString("application/problem+json")));
  }

  @Test
  @DisplayName("Vary names Accept, so a cache cannot hand a CBOR body to a JSON client")
  void varyNamesAccept() throws Exception {
    java.util.List<String> vary =
        mockMvc
            .perform(get("/api/v1/job-types").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getHeaders(HttpHeaders.VARY);

    assertThat(vary).contains("Accept", "Accept-Encoding");
  }
}
