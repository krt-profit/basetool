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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import de.greluc.krt.profit.basetool.ingest.model.dto.HandoffKind;
import de.greluc.krt.profit.basetool.ingest.service.BackendImportClient;
import de.greluc.krt.profit.basetool.ingest.service.HandoffStagingService;
import de.greluc.krt.profit.basetool.ingest.support.LogCapture;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * End-to-end web-layer test for the gateway: exercises the controller, the {@code IngestService}
 * orchestration, the security matrix and the RFC 7807 advice with the externals (backend relay,
 * Redis staging, JWT decoder) mocked (REQ-INGEST-001..004).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class IngestControllerTest {

  private static final String REFINERY_BODY =
      "{\"schemaVersion\":1,\"orders\":[{\"panelType\":\"SETUP\","
          + "\"sourceImages\":[{\"name\":\"shot.png\",\"width\":1920,\"height\":1080,"
          + "\"cropMode\":\"vlm\"}],"
          + "\"goods\":[{\"rawMaterialName\":\"Iron\",\"inputQuantity\":1,\"refine\":true}]}]}";

  @Autowired private WebApplicationContext context;

  @MockitoBean private JwtDecoder jwtDecoder;
  @MockitoBean private BackendImportClient backendImportClient;
  @MockitoBean private HandoffStagingService handoffStagingService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void shouldStageRefineryDraftAndReturnHandoff() throws Exception {
    when(backendImportClient.forwardRefineryExtract(anyString(), any(), any()))
        .thenReturn("{\"goodsMatched\":1}");
    when(handoffStagingService.stage(anyString(), eq(HandoffKind.REFINERY), anyString()))
        .thenReturn("HID123");

    mockMvc
        .perform(
            post("/v1/refinery-extract")
                .with(jwt().jwt(j -> j.subject("user-1").tokenValue("tok")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(REFINERY_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.handoffId").value("HID123"))
        .andExpect(jsonPath("$.kind").value("REFINERY"))
        .andExpect(
            jsonPath("$.frontendUrl")
                .value(org.hamcrest.Matchers.containsString("handoff=HID123")));
  }

  @Test
  void shouldRejectUnauthenticatedCaller() throws Exception {
    // The 401 used to be an empty body; it now carries the same RFC 7807 shape as every other
    // ingest error so the extractor can branch on `code` (REQ-API-004), while keeping the RFC 6750
    // challenge its OAuth client reads.
    mockMvc
        .perform(
            post("/v1/refinery-extract")
                .contentType(MediaType.APPLICATION_JSON)
                .content(REFINERY_BODY))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
        .andExpect(
            header()
                .string(HttpHeaders.WWW_AUTHENTICATE, org.hamcrest.Matchers.startsWith("Bearer")));
  }

  @Test
  void shouldReject400OnInvalidExtract() throws Exception {
    mockMvc
        .perform(
            post("/v1/refinery-extract")
                .with(jwt().jwt(j -> j.subject("user-1").tokenValue("tok")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"schemaVersion\":1,\"orders\":[]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  @Test
  void shouldStageBlueprintPreview() throws Exception {
    when(backendImportClient.forwardBlueprintPreview(anyString(), any(), any()))
        .thenReturn("{\"total\":2}");
    when(handoffStagingService.stage(anyString(), eq(HandoffKind.BLUEPRINT), anyString()))
        .thenReturn("BP1");

    mockMvc
        .perform(
            post("/v1/blueprint-preview")
                .with(jwt().jwt(j -> j.subject("user-1").tokenValue("tok")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"blueprints\":[]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("BLUEPRINT"));
  }

  @Test
  void shouldReject400WhenBlueprintBodyIsNotAnObject() throws Exception {
    mockMvc
        .perform(
            post("/v1/blueprint-preview")
                .with(jwt().jwt(j -> j.subject("user-1").tokenValue("tok")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("[]"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
  }

  @Test
  void shouldRelayBackend4xxVerbatim() throws Exception {
    when(backendImportClient.forwardRefineryExtract(anyString(), any(), any()))
        .thenThrow(
            WebClientResponseException.create(
                HttpStatus.BAD_REQUEST.value(),
                "Bad Request",
                HttpHeaders.EMPTY,
                new byte[0],
                null));

    mockMvc
        .perform(
            post("/v1/refinery-extract")
                .with(jwt().jwt(j -> j.subject("user-1").tokenValue("tok")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(REFINERY_BODY))
        .andExpect(status().isBadRequest());
  }

  @Test
  void shouldReject400OnAMalformedJsonBody() throws Exception {
    // Exercises the ResponseEntityExceptionHandler override: a body Jackson cannot read must come
    // back as the same RFC 7807 shape as every other gateway problem, not as a container error.
    mockMvc
        .perform(
            post("/v1/refinery-extract")
                .with(jwt().jwt(j -> j.subject("user-1").tokenValue("tok")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"schemaVersion\":"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
        .andExpect(jsonPath("$.title").value("Malformed request body"));
  }

  @Test
  void shouldListTheFieldErrorsOnAValidationRejectAndAlsoLogThem() throws Exception {
    // "Mein Extrakt wird abgelehnt" is the most common support question; until the WARN existed the
    // failing constraint lived only in the response body, so the operator had to ask for it back.
    List<ILoggingEvent> events =
        LogCapture.capture(
            GlobalExceptionHandler.class,
            Level.INFO,
            () ->
                mockMvc
                    .perform(
                        post("/v1/refinery-extract")
                            .with(jwt().jwt(j -> j.subject("user-1").tokenValue("tok")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"schemaVersion\":1,\"orders\":[]}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors").isArray())
                    .andExpect(
                        jsonPath("$.fieldErrors[0]")
                            .value(org.hamcrest.Matchers.containsString("orders"))));

    assertThat(events).hasSize(1);
    assertThat(events.getFirst().getLevel()).isEqualTo(Level.WARN);
    assertThat(events.getFirst().getFormattedMessage())
        .startsWith("Ingest payload rejected by validation: ")
        .contains("orders");
  }

  @Test
  void shouldLogAMalformedBodyWithoutEchoingItsContent() throws Exception {
    // Jackson's message quotes the offending part of the body — here a user's extract — so only
    // the exception class may be logged (REQ-OBS-004).
    List<ILoggingEvent> events =
        LogCapture.capture(
            GlobalExceptionHandler.class,
            Level.INFO,
            () ->
                mockMvc
                    .perform(
                        post("/v1/refinery-extract")
                            .with(jwt().jwt(j -> j.subject("user-1").tokenValue("tok")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"tool\":\"secret-internal-build\","))
                    .andExpect(status().isBadRequest()));

    assertThat(events).hasSize(1);
    assertThat(events.getFirst().getLevel()).isEqualTo(Level.WARN);
    assertThat(events.getFirst().getFormattedMessage())
        .startsWith("Ingest body could not be parsed as JSON (")
        .doesNotContain("secret-internal-build");
  }

  @Test
  void shouldServeTheOpenApiDocumentWithoutAuthentication() throws Exception {
    // The committed spec is generated from this endpoint; it is permitted in non-prod and disabled
    // outright in prod (springdoc.api-docs.enabled=false).
    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.info.title").value("KRT Basetool Ingest Gateway API"));
  }

  @Test
  void shouldReturn502WhenBackendUnreachable() throws Exception {
    when(backendImportClient.forwardRefineryExtract(anyString(), any(), any()))
        .thenThrow(
            new WebClientRequestException(
                new RuntimeException("connection refused"),
                HttpMethod.POST,
                URI.create("https://backend:11261/api/v1/refinery-orders/import-extract"),
                HttpHeaders.EMPTY));

    mockMvc
        .perform(
            post("/v1/refinery-extract")
                .with(jwt().jwt(j -> j.subject("user-1").tokenValue("tok")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(REFINERY_BODY))
        .andExpect(status().isBadGateway());
  }
}
