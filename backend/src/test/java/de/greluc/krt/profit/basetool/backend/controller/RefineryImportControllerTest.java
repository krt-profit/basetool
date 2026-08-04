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

package de.greluc.krt.profit.basetool.backend.controller;

import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.model.dto.RefineryImportDraftDto;
import de.greluc.krt.profit.basetool.backend.service.RefineryImportService;
import de.greluc.krt.profit.basetool.backend.service.UserService;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Web-layer tests for {@link RefineryImportController} (#434): the {@code isAuthenticated()} gate
 * (401 anonymous, 200 any authenticated role), the envelope-level 400s with their i18n problem
 * detail, and the bean-validation 400 on a contract-cap violation. The service is
 * {@code @MockitoBean}-stubbed — matching logic is covered by {@code RefineryImportServiceTest}.
 */
@SpringBootTest
@ActiveProfiles("test")
class RefineryImportControllerTest {

  private static final String ENDPOINT = "/api/v1/refinery-orders/import-extract";

  /** A realistic member subject: the backend resolves every owner as a UUID (ADR-0129). */
  private static final String MEMBER_SUB = "33333333-3333-3333-3333-333333333333";

  private static final String VALID_BODY =
      """
      {
        "schemaVersion": 1,
        "orders": [
          {
            "panelType": "SETUP",
            "quoted": true,
            "sourceImages": [
              { "name": "panel.png", "width": 1920, "height": 1080, "cropMode": "vlm" }
            ],
            "goods": [
              {
                "rawMaterialName": "STILERON (ORE)",
                "quality": 618,
                "inputQuantity": 957,
                "outputQuantity": 448,
                "refine": true
              }
            ]
          }
        ]
      }
      """;

  @Autowired private WebApplicationContext context;

  @MockitoBean private JwtDecoder jwtDecoder;

  @MockitoBean private RefineryImportService refineryImportService;

  @MockitoBean private UserService userService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    when(userService.getUserIdFromJwt(any())).thenReturn(UUID.randomUUID());
  }

  @Test
  void importExtract_unauthenticated_returns401() throws Exception {
    mockMvc
        .perform(
            post(ENDPOINT).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void importExtract_authenticatedMember_returnsDraft() throws Exception {
    // Given
    when(refineryImportService.buildDraft(any(), any()))
        .thenReturn(new RefineryImportDraftDto(null, List.of(), 1, 1, 0));

    // When / Then — plain membership suffices, no elevated role required.
    // The subject is a real UUID: since ADR-0129 the owner comes from the SecurityContext, which
    // ActingMemberFilter has already swapped to the acting member,
    // which is fail-closed on a non-UUID sub exactly as UserService#getUserIdFromJwt always was.
    // The default `user` subject only ever worked here because userService was mocked away.
    mockMvc
        .perform(
            post(ENDPOINT)
                .with(
                    jwt()
                        .jwt(token -> token.subject(MEMBER_SUB))
                        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.goodsMatched").value(1))
        .andExpect(jsonPath("$.goodsTotal").value(1))
        .andExpect(jsonPath("$.rowsSkipped").value(0));
  }

  @Test
  void importExtract_unsupportedSchemaVersion_returns400WithLocalizedDetail() throws Exception {
    // Given — the service rejects the envelope with the i18n key
    when(refineryImportService.buildDraft(any(), any()))
        .thenThrow(new BadRequestException("error.refineryImport.unsupportedSchemaVersion"));

    // When / Then — GlobalExceptionHandler resolves the key to a human-readable detail
    mockMvc
        .perform(
            post(ENDPOINT)
                .with(
                    jwt()
                        .jwt(token -> token.subject(MEMBER_SUB))
                        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.detail").value(not("error.refineryImport.unsupportedSchemaVersion")));
  }

  @Test
  void importExtract_processingPanel_returns400WithLocalizedDetail() throws Exception {
    // Given
    when(refineryImportService.buildDraft(any(), any()))
        .thenThrow(new BadRequestException("error.refineryImport.unsupportedPanelType"));

    // When / Then
    mockMvc
        .perform(
            post(ENDPOINT)
                .with(
                    jwt()
                        .jwt(token -> token.subject(MEMBER_SUB))
                        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY.replace("SETUP", "PROCESSING")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(not("error.refineryImport.unsupportedPanelType")));
  }

  @Test
  void importExtract_missingOrders_returns400FromBeanValidation() throws Exception {
    // Given — @NotEmpty on orders fires before the service is reached
    String emptyOrders = "{\"schemaVersion\": 1, \"orders\": []}";

    // When / Then
    mockMvc
        .perform(
            post(ENDPOINT)
                .with(
                    jwt()
                        .jwt(token -> token.subject(MEMBER_SUB))
                        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(emptyOrders))
        .andExpect(status().isBadRequest());
  }

  @Test
  void importExtract_nullOrderElement_returns400FromBeanValidation() throws Exception {
    // Given — Hibernate Validator skips null list elements unless the container element carries
    // @NotNull; without it this payload would NPE in the service and surface as a 500
    String nullElement = "{\"schemaVersion\": 1, \"orders\": [null]}";

    // When / Then
    mockMvc
        .perform(
            post(ENDPOINT)
                .with(
                    jwt()
                        .jwt(token -> token.subject(MEMBER_SUB))
                        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(nullElement))
        .andExpect(status().isBadRequest());
  }

  @Test
  void importExtract_nullGoodElement_returns400FromBeanValidation() throws Exception {
    // Given — same guard on the goods list
    String body =
        "{\"schemaVersion\":1,\"orders\":[{\"panelType\":\"SETUP\",\"quoted\":true,"
            + "\"goods\":[{\"rawMaterialName\":\"STILERON (ORE)\",\"quality\":618,"
            + "\"inputQuantity\":957,\"outputQuantity\":448,\"refine\":true},null]}]}";

    // When / Then
    mockMvc
        .perform(
            post(ENDPOINT)
                .with(
                    jwt()
                        .jwt(token -> token.subject(MEMBER_SUB))
                        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void importExtract_tooManyOrders_returns400FromSizeCap() throws Exception {
    // Given — the defensive @Size(max = 5) cap on orders
    String order =
        """
        { "panelType": "SETUP", "quoted": true, "goods": [] }\
        """;
    String sixOrders =
        "{\"schemaVersion\": 1, \"orders\": ["
            + String.join(",", Collections.nCopies(6, order))
            + "]}";

    // When / Then
    mockMvc
        .perform(
            post(ENDPOINT)
                .with(
                    jwt()
                        .jwt(token -> token.subject(MEMBER_SUB))
                        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(sixOrders))
        .andExpect(status().isBadRequest());
  }

  @Test
  void importExtract_missingRawMaterialName_returns400FromBeanValidation() throws Exception {
    // Given — per-good @NotNull cascades through orders/goods via @Valid
    String body = VALID_BODY.replace("\"rawMaterialName\": \"STILERON (ORE)\",", "");

    // When / Then
    mockMvc
        .perform(
            post(ENDPOINT)
                .with(
                    jwt()
                        .jwt(token -> token.subject(MEMBER_SUB))
                        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
  }
}
