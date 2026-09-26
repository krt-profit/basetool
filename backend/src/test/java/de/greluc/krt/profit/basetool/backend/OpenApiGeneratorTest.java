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
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
@Slf4j
class OpenApiGeneratorTest {

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
    if (path.getParent() != null) {
      Files.createDirectories(path.getParent());
    }
    writeAtomically(path, jsonObject);

    log.info("OpenAPI documentation generated at: {}", path.toAbsolutePath());
  }

  /**
   * Writes {@code document} to a temporary file in the target's directory and moves it into place,
   * so concurrent readers of the committed spec never see a partial file.
   *
   * @param target the committed spec path to replace
   * @param document the OpenAPI document to serialize
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
      Files.deleteIfExists(temporary);
    }
  }
}
