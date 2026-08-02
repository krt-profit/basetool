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
import de.greluc.krt.profit.basetool.ingest.logging.LogSafe;
import de.greluc.krt.profit.basetool.ingest.metrics.MetricNames;
import de.greluc.krt.profit.basetool.ingest.model.dto.HandoffKind;
import de.greluc.krt.profit.basetool.ingest.model.dto.IngestResponseDto;
import de.greluc.krt.profit.basetool.ingest.model.dto.RefineryExtractDto;
import de.greluc.krt.profit.basetool.ingest.model.dto.RefineryExtractOrderDto;
import de.greluc.krt.profit.basetool.ingest.ratelimit.SubjectRateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
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

  /** Cap on a logged provenance string, so a padded {@code tool} field cannot bloat a log line. */
  private static final int MAX_LOGGED_PROVENANCE = 60;

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
    logAcceptedExtract(extract);
    String draftJson =
        backendImportClient.forwardRefineryExtract(bearer, acceptLanguage, correlationId, extract);
    String handoffId = handoffStagingService.stage(sub, HandoffKind.REFINERY, draftJson);
    countHandoff(HandoffKind.REFINERY);
    return response(handoffId, HandoffKind.REFINERY, ingestProperties.getRefineryPath());
  }

  /**
   * Records the <em>shape</em> of an accepted extract before it is relayed: the contract version,
   * the producing tool, and how many orders / goods rows / source images it carries. Without this
   * line an extractor that sends a structurally odd payload (a v2 envelope, zero goods, fifty
   * stitched images) is indistinguishable in the log from a normal send — the gateway itself
   * interprets nothing, so the counts are the only handle on "what did the client actually push?".
   *
   * <p>No screen read and no material name is logged: only counts, the numeric schema version and
   * the provenance strings, and those go through {@link LogSafe} because they are client-supplied
   * free text that could otherwise forge a second log line.
   *
   * @param extract the validated extract about to be forwarded
   */
  private void logAcceptedExtract(@NotNull RefineryExtractDto extract) {
    if (!log.isInfoEnabled()) {
      return;
    }
    List<RefineryExtractOrderDto> orders = extract.orders() == null ? List.of() : extract.orders();
    int goods = 0;
    int images = 0;
    for (RefineryExtractOrderDto order : orders) {
      goods += order.goods() == null ? 0 : order.goods().size();
      images += order.sourceImages() == null ? 0 : order.sourceImages().size();
    }
    log.info(
        "Relaying refinery extract (schemaVersion={}, tool={}/{}, orders={}, goods={}, images={})",
        extract.schemaVersion(),
        LogSafe.text(extract.tool(), MAX_LOGGED_PROVENANCE),
        LogSafe.text(extract.toolVersion(), MAX_LOGGED_PROVENANCE),
        orders.size(),
        goods,
        images);
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
    // The export is opaque to the gateway, so its size is the only shape there is to record — and
    // it is what separates "the extractor sent an empty file" from a genuine backend reject.
    log.info("Relaying blueprint export ({} bytes)", blueprintJson.length);
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
