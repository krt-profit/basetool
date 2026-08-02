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

package de.greluc.krt.profit.basetool.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Regenerates the committed ingest OpenAPI document from the live SpringDoc output, the gateway
 * twin of the backend {@code OpenApiGeneratorTest}. The committed {@code
 * src/main/resources/api/openapi.json} is the module's single API-documentation artifact — no
 * Swagger UI is bundled and {@code /v3/api-docs} is disabled in prod — so it must be regenerated
 * (and committed) whenever an endpoint, DTO or response contract changes.
 *
 * <p>Beyond writing the file, the test asserts the document's load-bearing parts so a silent
 * regression (a lost security scheme, a dropped endpoint, a controller that stopped being scanned)
 * fails the build rather than quietly shrinking the committed spec.
 */
@SpringBootTest
class OpenApiGeneratorTest {

  private static final Logger log = LoggerFactory.getLogger(OpenApiGeneratorTest.class);

  @Autowired private WebApplicationContext context;

  @MockitoBean private JwtDecoder jwtDecoder;

  private final JsonMapper objectMapper = JsonMapper.builder().build();

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void generateOpenApiDocs() throws Exception {
    MvcResult result = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();

    String json = result.getResponse().getContentAsString();
    JsonNode document = objectMapper.readTree(json);

    assertThat(document.path("info").path("title").asString())
        .isEqualTo("KRT Basetool Ingest Gateway API");
    assertThat(document.path("components").path("securitySchemes").has("bearer-jwt")).isTrue();
    // The gateway's entire public surface (REQ-INGEST-001): exactly these two forward-only POSTs.
    assertThat(document.path("paths").propertyNames())
        .containsExactlyInAnyOrder("/v1/refinery-extract", "/v1/blueprint-preview");
    assertThat(document.path("paths").path("/v1/refinery-extract").has("post")).isTrue();
    assertThat(document.path("paths").path("/v1/blueprint-preview").has("post")).isTrue();
    // A stale spec that lost the request schema would still have the paths — pin the DTO too.
    assertThat(document.path("components").path("schemas").has("RefineryExtractDto")).isTrue();
    assertThat(document.path("components").path("schemas").has("IngestResponseDto")).isTrue();

    Path path = Paths.get("src/main/resources/api/openapi.json");
    if (path.getParent() != null) {
      Files.createDirectories(path.getParent());
    }
    writeAtomically(path, objectMapper.readValue(json, Object.class));

    log.info("Ingest OpenAPI documentation generated at: {}", path.toAbsolutePath());
  }

  /**
   * Serializes {@code document} into {@code target} via a temporary sibling file that is then moved
   * into place, so the committed spec is never observable half-written — the same guard the backend
   * generator carries, because {@code org.gradle.parallel=true} lets the module test tasks run
   * concurrently with anything that reads the committed specs.
   *
   * @param target the committed spec path to replace
   * @param document the parsed OpenAPI document to serialize
   * @throws IOException if the document cannot be written or moved into place
   */
  private void writeAtomically(Path target, Object document) throws IOException {
    Path directory = target.getParent() == null ? Paths.get(".") : target.getParent();
    Path temporary = Files.createTempFile(directory, "openapi-", ".json.tmp");
    try {
      objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), document);
      try {
        Files.move(
            temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        log.debug("Atomic move unsupported for {}; falling back to a plain replace.", target, e);
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      // A successful move already consumed the temporary file; this only cleans up after a failure.
      Files.deleteIfExists(temporary);
    }
  }
}
