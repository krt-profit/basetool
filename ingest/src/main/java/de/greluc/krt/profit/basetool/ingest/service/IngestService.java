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

package de.greluc.krt.profit.basetool.ingest.service;

import de.greluc.krt.profit.basetool.ingest.config.IngestProperties;
import de.greluc.krt.profit.basetool.ingest.metrics.MetricNames;
import de.greluc.krt.profit.basetool.ingest.model.dto.HandoffKind;
import de.greluc.krt.profit.basetool.ingest.model.dto.IngestResponseDto;
import de.greluc.krt.profit.basetool.ingest.model.dto.RefineryExtractDto;
import de.greluc.krt.profit.basetool.ingest.ratelimit.SubjectRateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

/**
 * Orchestrates a single ingest: relay the payload to the backend, stage the returned draft for a
 * one-time browser pickup, and build the {@link IngestResponseDto} the extractor opens. This is the
 * whole of the gateway's business logic — it interprets nothing, persists nothing durable, and
 * mints no token (REQ-INGEST-001/-004).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestService {

  private final BackendImportClient backendImportClient;
  private final HandoffStagingService handoffStagingService;
  private final IngestProperties ingestProperties;
  private final SubjectRateLimiter subjectRateLimiter;
  private final MeterRegistry meterRegistry;

  /**
   * Relays a refinery extract to the backend, stages the resulting draft, and returns the handoff.
   *
   * @param sub the authenticated caller's subject (scopes the staged handoff)
   * @param bearer the caller's raw JWT, forwarded to the backend
   * @param acceptLanguage the caller's resolved locale (relayed; may be {@code null})
   * @param correlationId the request correlation id (relayed; may be {@code null})
   * @param extract the validated extract payload
   * @return the handoff id, kind and frontend URL for the extractor to open
   */
  public @NotNull IngestResponseDto ingestRefinery(
      @NotNull String sub,
      @NotNull String bearer,
      String acceptLanguage,
      String correlationId,
      @NotNull RefineryExtractDto extract) {
    // Per-subject throttle (REQ-INGEST-005): bound how hard one authenticated caller can drive the
    // backend import endpoints. Checked before the backend relay so an over-budget caller is
    // rejected without forwarding.
    subjectRateLimiter.requireWithinLimit(sub);
    String draftJson =
        backendImportClient.forwardRefineryExtract(bearer, acceptLanguage, correlationId, extract);
    String handoffId = handoffStagingService.stage(sub, HandoffKind.REFINERY, draftJson);
    countHandoff(HandoffKind.REFINERY);
    return response(handoffId, HandoffKind.REFINERY, ingestProperties.getRefineryPath());
  }

  /**
   * Relays a blueprint export to the backend's preview, stages the resulting preview, and returns
   * the handoff.
   *
   * @param sub the authenticated caller's subject (scopes the staged handoff)
   * @param bearer the caller's raw JWT, forwarded to the backend
   * @param acceptLanguage the caller's resolved locale (relayed; may be {@code null})
   * @param correlationId the request correlation id (relayed; may be {@code null})
   * @param blueprintJson the blueprint export JSON bytes to forward as the upload
   * @return the handoff id, kind and frontend URL for the extractor to open
   */
  public @NotNull IngestResponseDto ingestBlueprint(
      @NotNull String sub,
      @NotNull String bearer,
      String acceptLanguage,
      String correlationId,
      byte @NotNull [] blueprintJson) {
    // Per-subject throttle (REQ-INGEST-005); see ingestRefinery for the rationale.
    subjectRateLimiter.requireWithinLimit(sub);
    String draftJson =
        backendImportClient.forwardBlueprintPreview(
            bearer, acceptLanguage, correlationId, blueprintJson);
    String handoffId = handoffStagingService.stage(sub, HandoffKind.BLUEPRINT, draftJson);
    countHandoff(HandoffKind.BLUEPRINT);
    return response(handoffId, HandoffKind.BLUEPRINT, ingestProperties.getBlueprintPath());
  }

  /**
   * Increments {@code basetool_ingest_handoff_total} for a successfully relayed and staged handoff,
   * tagged by the bounded {@link HandoffKind} (REQ-OBS-011). Never tagged by the subject or the
   * payload.
   *
   * @param kind the staged draft kind
   */
  private void countHandoff(@NotNull HandoffKind kind) {
    meterRegistry
        .counter(MetricNames.INGEST_HANDOFF, MetricNames.TAG_KIND, kind.name())
        .increment();
  }

  /**
   * Builds the response DTO, assembling the absolute frontend URL that carries the handoff id.
   *
   * @param handoffId the staged handoff id
   * @param kind the staged draft kind
   * @param path the frontend path to open (without the query)
   * @return the assembled response
   */
  private @NotNull IngestResponseDto response(
      @NotNull String handoffId, @NotNull HandoffKind kind, @NotNull String path) {
    String url = ingestProperties.getFrontendBaseUrl() + path + "?handoff=" + handoffId;
    return new IngestResponseDto(handoffId, kind, url);
  }
}
