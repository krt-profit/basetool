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

package de.greluc.krt.profit.basetool.backend.catalogue.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.P4kTestCatalogues;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.node.ObjectNode;

/**
 * The upload gate over HTTP (REQ-P4K-001): a refused catalogue is a {@code 400} problem with code
 * {@code P4K_UPLOAD_REJECTED} and its reason, and no job is created. The Channel rule has no
 * property, so the {@code test} profile answers exactly as {@code dev} and {@code prod}.
 */
@SpringBootTest
class P4kImportControllerMvcTest {

  private static final String JOBS = "/api/v1/catalog/admin/import/p4k/jobs";

  @Autowired private WebApplicationContext context;
  @Autowired private P4kImportJobRepository jobRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @ParameterizedTest
  @ValueSource(strings = {"PTU", "EPTU", "TECH-PREVIEW", "HOTFIX"})
  void aTestChannelCatalogue_isRefusedWithoutAJob(String channel) throws Exception {
    ObjectNode root = P4kTestCatalogues.tree();
    ((ObjectNode) root.get("source")).put("channel", channel);
    long jobsBefore = jobRepository.count();

    mockMvc
        .perform(
            multipart(JOBS)
                .file(
                    new MockMultipartFile(
                        "file",
                        "catalogue.json.zst",
                        "application/octet-stream",
                        P4kTestCatalogues.gzip(P4kTestCatalogues.bytes(root))))
                .with(
                    jwt()
                        .jwt(token -> token.subject(UUID.randomUUID().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("P4K_UPLOAD_REJECTED"))
        .andExpect(jsonPath("$.reason").value("channel"));

    assertThat(jobRepository.count()).isEqualTo(jobsBefore);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 3})
  void anotherSchemaVersion_isRefusedWithoutAJob(int version) throws Exception {
    ObjectNode root = P4kTestCatalogues.tree();
    root.put("schemaVersion", version);
    long jobsBefore = jobRepository.count();

    mockMvc
        .perform(
            multipart(JOBS)
                .file(
                    new MockMultipartFile(
                        "file",
                        "catalogue.json",
                        "application/json",
                        P4kTestCatalogues.bytes(root)))
                .with(
                    jwt()
                        .jwt(token -> token.subject(UUID.randomUUID().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.reason").value("schema_version"));

    assertThat(jobRepository.count()).isEqualTo(jobsBefore);
  }
}
