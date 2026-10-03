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

package de.greluc.krt.profit.basetool.ingest.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.ingest.contract.ExchangeDocuments;
import de.greluc.krt.profit.basetool.ingest.handoff.HandoffStagingService;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ExchangeDocumentsControllerTest {

  @Autowired private WebApplicationContext context;
  @Autowired private ExchangeDocuments documents;

  @MockitoBean private JwtDecoder jwtDecoder;
  @MockitoBean private HandoffStagingService handoffStagingService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void theOpenApiDocumentIsServedAnonymouslyAndUnchanged() throws Exception {
    mockMvc
        .perform(get("/exchange/v1/openapi.json"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(header().string("Cache-Control", "max-age=3600, public"))
        .andExpect(content().bytes(committed("/api/exchange-v1.openapi.json")));
  }

  @Test
  void everyCommittedSchemaIsServedAnonymouslyAtItsName() throws Exception {
    assertThat(documents.schemaNames()).hasSizeGreaterThan(20).contains("item-ref.schema.json");
    for (String name : documents.schemaNames()) {
      mockMvc
          .perform(get("/exchange/v1/schemas/" + name))
          .andExpect(status().isOk())
          .andExpect(content().contentTypeCompatibleWith("application/schema+json"))
          .andExpect(content().bytes(committed("/exchange/v1/schemas/" + name)));
    }
  }

  @Test
  void anUnknownSchemaIsANotFoundProblem() throws Exception {
    mockMvc
        .perform(get("/exchange/v1/schemas/no-such.schema.json"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void aTraversalAttemptFindsNothing() throws Exception {
    mockMvc
        .perform(get("/exchange/v1/schemas/..%2F..%2Fapplication.yml"))
        .andExpect(status().is4xxClientError());
  }

  @Test
  void writesToTheDocumentsNeedAToken() throws Exception {
    mockMvc
        .perform(post("/exchange/v1/openapi.json").contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized());
  }

  /**
   * Reads a committed document from the classpath.
   *
   * @param path the classpath path
   * @return its bytes
   * @throws IOException if it cannot be read
   */
  private byte[] committed(String path) throws IOException {
    try (InputStream in = getClass().getResourceAsStream(path)) {
      assertThat(in).as(path).isNotNull();
      return in.readAllBytes();
    }
  }
}
