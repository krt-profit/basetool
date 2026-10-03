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
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.api.CommittedOpenApi;
import de.greluc.krt.profit.basetool.backend.api.ExchangeFence;
import de.greluc.krt.profit.basetool.backend.api.ExposedTypes;
import de.greluc.krt.profit.basetool.backend.api.OpenApiDocumentAssertions;
import de.greluc.krt.profit.basetool.backend.config.ContractTiers;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Generates the committed {@code openapi.json} and the exchange's internal relay document {@code
 * exchange-relay.openapi.json} from the running controllers, after asserting what each must satisfy
 * (REQ-API-007, REQ-API-018, REQ-XCH-039).
 *
 * <p>Outside CI a stale document is rewritten; in CI ({@code CI=true}) it fails the test instead.
 */
@SpringBootTest
@Slf4j
class OpenApiGeneratorTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  private final JsonMapper objectMapper = JsonMapper.builder().build();

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /**
   * Fetches the full document, splits off the relay surface, refuses a wrong document, and writes
   * both or reports which is stale.
   *
   * @throws Exception if the request, the assertions' inputs or a write fail
   */
  @Test
  void generateOpenApiDocs() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                get("/v3/api-docs")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andReturn();

    JsonNode full = objectMapper.readTree(result.getResponse().getContentAsString());
    ExchangeFence.Split split = ExchangeFence.split(full);
    ContractTiers tiers = ContractTiers.load();

    assertThat(
            OpenApiDocumentAssertions.problems(
                split.published(), tiers, OpenApiDocumentAssertions.DOMAIN_FLOOR))
        .as(
            "the generated document is wrong and is not written (REQ-API-007, REQ-API-018): the"
                + " security scheme, the two anonymous operations, one domain tag and one contract"
                + " tier per operation, each domain's operation-count floor, no relay path and no"
                + " dangling reference")
        .isEmpty();
    assertThat(OpenApiDocumentAssertions.relayProblems(split.relay(), tiers))
        .as(
            "the generated exchange relay document is wrong and is not written (REQ-XCH-039):"
                + " exactly the 14 relay operations, all exchange, all T0, complete schemas")
        .isEmpty();
    assertThat(ExposedTypes.collisions(ExposedTypes.bySchemaName(ExposedTypes.controllers())))
        .as(
            "two exposed Java types get one schema name, so the document describes only one of"
                + " them; give each an explicit @Schema(name = ...) and keep the name an app"
                + " build already knows (REQ-API-018)")
        .isEmpty();

    boolean rewrite = !"true".equalsIgnoreCase(System.getenv("CI"));
    List<String> stale = new ArrayList<>();
    if (CommittedOpenApi.refresh(
        CommittedOpenApi.PUBLISHED_FILE, CommittedOpenApi.render(split.published()), rewrite)) {
      stale.add(CommittedOpenApi.PUBLISHED_FILE.toString());
    }
    if (CommittedOpenApi.refresh(
        CommittedOpenApi.RELAY_FILE, CommittedOpenApi.render(split.relay()), rewrite)) {
      stale.add(CommittedOpenApi.RELAY_FILE.toString());
    }
    log.info("OpenAPI documents generated; rewritten: {}", stale);
  }
}
