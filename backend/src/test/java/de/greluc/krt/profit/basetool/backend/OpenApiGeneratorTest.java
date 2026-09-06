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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@ActiveProfiles("test")
class OpenApiGeneratorTest {

  private static final Logger log = LoggerFactory.getLogger(OpenApiGeneratorTest.class);

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean private JwtDecoder jwtDecoder;

  private final JsonMapper objectMapper = JsonMapper.builder().build();

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void generateOpenApiDocs() throws Exception {
    // REQ-SEC-052: the document is admin-gated now — it enumerates every path, parameter and DTO
    // field the API has, which is the most efficient description of the attack surface the project
    // can produce, and it was readable without a token on every profile that serves it. Being a 404
    // in prod is a deployment property, not an access rule.
    MvcResult result =
        mockMvc
            .perform(
                get("/v3/api-docs")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(status().isOk())
            .andReturn();

    String json = result.getResponse().getContentAsString();
    Object jsonObject = objectMapper.readValue(json, Object.class);

    Path path = Paths.get("src/main/resources/api/openapi.json");
    // Ensure the directory exists
    if (path.getParent() != null) {
      Files.createDirectories(path.getParent());
    }
    writeAtomically(path, jsonObject);

    log.info("OpenAPI documentation generated at: {}", path.toAbsolutePath());
  }

  /**
   * Serializes {@code document} into {@code target} via a temporary sibling file that is then moved
   * into place, so the committed spec is never observable half-written.
   *
   * <p>This matters because {@code org.gradle.parallel=true} lets {@code :backend:test} and {@code
   * :frontend:test} run at the same time, and four frontend contract tests ({@code
   * DtoOpenApiContractTest}, {@code FrontendDtoContractTest}, …) read this very file with {@code
   * Files.readString}. Writing in place truncated the 1.8&nbsp;MB document and streamed it back
   * over several hundred milliseconds; a reader that hit that window parsed a cut-off document and
   * failed with {@code UnexpectedEndOfInputException} — a flake that only surfaced when the two
   * test tasks happened to overlap on the wrong side (release/v1.5.23, while the identical tree
   * passed on {@code main} minutes earlier). Moving a fully-written file into place means a
   * concurrent reader always sees one complete version, old or new; since the generated document
   * must equal the committed one anyway, either is a valid read.
   *
   * <p>The temporary file is created <em>in the target's own directory</em> so the move stays
   * within one filesystem and can be atomic. A filesystem that cannot do atomic moves falls back to
   * a plain replace, which still writes the bytes elsewhere first and so keeps the truncation
   * window far smaller than an in-place write.
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
