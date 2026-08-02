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

import de.greluc.krt.profit.basetool.ingest.model.dto.IngestResponseDto;
import de.greluc.krt.profit.basetool.ingest.model.dto.RefineryExtractDto;
import de.greluc.krt.profit.basetool.ingest.service.IngestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The gateway's entire public surface: exactly two forward-only ingest endpoints (REQ-INGEST-001).
 * Both require only an authenticated caller (class- and method-level {@code @PreAuthorize},
 * mirroring the backend import endpoints), forward the caller's own bearer to the backend, stage
 * the returned draft for a one-time browser pickup, and return the handoff. Nothing here interprets
 * the draft or persists squadron data — saving happens later, in the browser, through the unchanged
 * create path (REQ-INGEST-004, REQ-REFINERY-002).
 */
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Tag(
    name = "Ingest",
    description =
        "Forward-only ingest of extractor payloads. Both endpoints relay to the backend with the"
            + " caller's own bearer and answer with a single-use browser handoff.")
public class IngestController {

  /** Media type every error response carries (RFC 7807); referenced from the response docs. */
  private static final String PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE;

  private final IngestService ingestService;
  private final ObjectMapper objectMapper;

  /**
   * Accepts a validated {@code RefineryExtract}, forwards it to the backend refinery import, and
   * returns the handoff the extractor opens.
   *
   * @param jwt the authenticated caller's token (its {@code sub} scopes the handoff; its raw value
   *     is the bearer forwarded to the backend)
   * @param acceptLanguage the caller's locale, relayed so backend problems are localized
   * @param correlationId the request correlation id, relayed for cross-module tracing
   * @param extract the validated extract payload
   * @return the handoff id, kind and frontend URL
   */
  @PostMapping(value = "/refinery-extract", consumes = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("isAuthenticated()")
  @Operation(
      summary =
          "Forward a RefineryExtract to the backend import and stage the draft for one-click"
              + " browser pickup.",
      description =
          "Accepts the frozen RefineryExtract JSON contract v1 (ADR-0008). The gateway validates"
              + " the envelope, forwards it to the backend refinery import with the caller's own"
              + " bearer, and stages the returned draft under the caller's subject. No screenshot"
              + " and no image bytes are ever transmitted or stored.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Draft staged; handoff returned.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = IngestResponseDto.class))),
    @ApiResponse(
        responseCode = "400",
        description = "Payload invalid or backend envelope reject.",
        content = @Content(mediaType = PROBLEM_JSON)),
    @ApiResponse(
        responseCode = "401",
        description = "Caller is not authenticated.",
        content = @Content(mediaType = PROBLEM_JSON)),
    @ApiResponse(
        responseCode = "413",
        description = "Payload exceeds the size cap.",
        content = @Content(mediaType = PROBLEM_JSON)),
    @ApiResponse(
        responseCode = "429",
        description = "Per-subject or per-IP rate limit exceeded.",
        content = @Content(mediaType = PROBLEM_JSON)),
    @ApiResponse(
        responseCode = "502",
        description = "Backend relay failed.",
        content = @Content(mediaType = PROBLEM_JSON)),
    @ApiResponse(
        responseCode = "503",
        description =
            "A dependency was temporarily unreachable (identity provider or handoff staging);"
                + " retry after the advertised delay.",
        content = @Content(mediaType = PROBLEM_JSON))
  })
  public @NotNull IngestResponseDto ingestRefineryExtract(
      @AuthenticationPrincipal Jwt jwt,
      @Parameter(description = "Caller locale, relayed so backend problems come back localized.")
          @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
          String acceptLanguage,
      @Parameter(description = "Correlation id, relayed and echoed for cross-module tracing.")
          @RequestHeader(value = "X-Correlation-Id", required = false)
          String correlationId,
      @Valid @RequestBody @NotNull RefineryExtractDto extract) {
    return ingestService.ingestRefinery(
        jwt.getSubject(), jwt.getTokenValue(), acceptLanguage, correlationId, extract);
  }

  /**
   * Accepts a blueprint export as a JSON object, forwards it to the backend's multipart preview,
   * and returns the handoff the extractor opens. The body is opaque to the gateway beyond the "must
   * be a JSON object" sanity check (mirroring the frontend proxy); the backend parses and matches
   * it.
   *
   * @param jwt the authenticated caller's token (its {@code sub} scopes the handoff; its raw value
   *     is the bearer forwarded to the backend)
   * @param acceptLanguage the caller's locale, relayed so backend problems are localized
   * @param correlationId the request correlation id, relayed for cross-module tracing
   * @param export the blueprint export JSON; must be a JSON object
   * @return the handoff id, kind and frontend URL
   */
  @PostMapping(value = "/blueprint-preview", consumes = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("isAuthenticated()")
  @Operation(
      summary =
          "Forward a blueprint export to the backend preview and stage it for one-click browser"
              + " pickup.",
      description =
          "The body is an opaque blueprint-export JSON object; the gateway only checks that it is a"
              + " JSON object and relays it to the backend preview as a file upload. The backend"
              + " parses and name-matches it, exactly as a manual file import would.",
      requestBody =
          @io.swagger.v3.oas.annotations.parameters.RequestBody(
              required = true,
              description =
                  "The blueprint export as produced by the extractor; must be a JSON"
                      + " object (an array or scalar is rejected with 400).",
              content =
                  @Content(
                      mediaType = MediaType.APPLICATION_JSON_VALUE,
                      schema =
                          @Schema(
                              type = "object",
                              additionalProperties = Schema.AdditionalPropertiesValue.TRUE))))
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Preview staged; handoff returned.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = IngestResponseDto.class))),
    @ApiResponse(
        responseCode = "400",
        description = "Body is not a JSON object or backend reject.",
        content = @Content(mediaType = PROBLEM_JSON)),
    @ApiResponse(
        responseCode = "401",
        description = "Caller is not authenticated.",
        content = @Content(mediaType = PROBLEM_JSON)),
    @ApiResponse(
        responseCode = "413",
        description = "Payload exceeds the size cap.",
        content = @Content(mediaType = PROBLEM_JSON)),
    @ApiResponse(
        responseCode = "429",
        description = "Per-subject or per-IP rate limit exceeded.",
        content = @Content(mediaType = PROBLEM_JSON)),
    @ApiResponse(
        responseCode = "502",
        description = "Backend relay failed.",
        content = @Content(mediaType = PROBLEM_JSON)),
    @ApiResponse(
        responseCode = "503",
        description =
            "A dependency was temporarily unreachable (identity provider or handoff staging);"
                + " retry after the advertised delay.",
        content = @Content(mediaType = PROBLEM_JSON))
  })
  public @NotNull IngestResponseDto ingestBlueprintPreview(
      @AuthenticationPrincipal Jwt jwt,
      @Parameter(description = "Caller locale, relayed so backend problems come back localized.")
          @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
          String acceptLanguage,
      @Parameter(description = "Correlation id, relayed and echoed for cross-module tracing.")
          @RequestHeader(value = "X-Correlation-Id", required = false)
          String correlationId,
      @RequestBody @NotNull JsonNode export) {
    if (!export.isObject()) {
      throw new BadRequestException("The blueprint export must be a JSON object.");
    }
    byte[] bytes = objectMapper.writeValueAsBytes(export);
    return ingestService.ingestBlueprint(
        jwt.getSubject(), jwt.getTokenValue(), acceptLanguage, correlationId, bytes);
  }
}
