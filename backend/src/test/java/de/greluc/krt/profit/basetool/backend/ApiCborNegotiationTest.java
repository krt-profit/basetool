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
    // The cache-control filter is a @Component and webAppContextSetup does not register filter
    // beans, so it is added the same way HttpCachingTest adds it -- without it the Vary case below
    // asserts nothing and would still have passed on the CORS-contributed values alone.
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

    // The claim §8.5 rests on, asserted rather than assumed: "same object model, only the bytes
    // change". Comparing the decoded trees is what proves the two representations carry the same
    // document -- a byte comparison could only ever show they differ, which is not the point.
    JsonNode fromCbor = CBOR.readTree(cbor);
    JsonNode fromJson = tools.jackson.databind.json.JsonMapper.builder().build().readTree(json);

    // This case USED TO BE VACUOUS and it cost five E2E write flows to find out. `JobTypeDto`
    // carries a `string/uuid` id, so this comparison should have caught UUIDs turning into CBOR
    // binary -- but the list is empty in the test context, so it compared two empty arrays and
    // passed. The floor is the whole lesson: a document comparison proves nothing about a document
    // with nothing in it. The type-level guarantee lives in CborJsonFidelityTest, which does not
    // depend on seeded data at all.
    JsonNode rows = fromJson.has("content") ? fromJson.get("content") : fromJson;
    if (rows.isEmpty()) {
      // Not a silent skip: say so, so that a reader knows which half of this class is live.
      org.junit.jupiter.api.Assumptions.abort(
          "no job types seeded, so this comparison would be vacuous — the type-level guarantee is"
              + " CborJsonFidelityTest, which needs no data");
    }
    assertThat(fromCbor).isEqualTo(fromJson);
  }

  @Test
  @DisplayName("a caller that does not ask for CBOR is completely unaffected")
  void jsonCallersAreUnaffected() throws Exception {
    // The Android app and the extractor send `Accept: application/json` and are shipped builds
    // that cannot be redeployed with the server (ADR-0136). Content negotiation is what keeps this
    // change invisible to them, so it is asserted here rather than reasoned about in a document.
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
    // Not a courtesy: GlobalExceptionHandler presets `application/problem+json` on the response,
    // and Spring skips Accept negotiation entirely for a preset concrete content type. If that
    // ever changed, the frontend would stop being able to read the stable machine-readable `code`
    // that krt-fetch.js routes reload-vs-toast on -- a failure that would look like a UI bug.
    mockMvc
        .perform(
            get("/api/v1/missions/00000000-0000-4000-8000-000000000000")
                .accept(MediaType.APPLICATION_CBOR))
        .andExpect(status().is4xxClientError())
        .andExpect(
            header()
                .string(
                    HttpHeaders.CONTENT_TYPE,
                    org.hamcrest.Matchers.containsString("application/problem+json")));
  }

  @Test
  @DisplayName("Vary names Accept, so a cache cannot hand a CBOR body to a JSON client")
  void varyNamesAccept() throws Exception {
    // The bug this change would otherwise have introduced. `no-cache, must-revalidate` lets an
    // intermediary STORE the body; before a second representation existed, keying on the URL alone
    // was sound. It is not any more.
    // Asserted as "contains", not as an exact list: Spring's CORS processor appends Origin and
    // the two Access-Control-Request-* names to the same header further down the chain, and
    // pinning the full set here would make this case fail on an unrelated CORS change.
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
