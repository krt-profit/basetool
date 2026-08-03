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

import de.greluc.krt.profit.basetool.ingest.config.ClientIdentityProperties;
import de.greluc.krt.profit.basetool.ingest.logging.LogSafe;
import de.greluc.krt.profit.basetool.ingest.metrics.MetricNames;
import de.greluc.krt.profit.basetool.ingest.model.dto.Provenance;
import de.greluc.krt.profit.basetool.ingest.web.ClientNotAllowedException;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Checks the payload's self-declared producer against the configured allowlist (REQ-INGEST-011,
 * {@code app.ingest.client-identity.allowed-tools}) — the payload-level companion to the
 * token-level checks in {@code ClientIdentityFilter}, which cannot run here because the body is not
 * parsed until the controller.
 *
 * <p><b>Be precise about what this is worth.</b> The {@code tool} field is client-supplied and the
 * contract that documents it is published (REQ-INGEST-010), so anyone who wants to set it to the
 * expected value will. It is <em>not</em> authentication and must never be counted as such. It
 * earns its place for two cheaper reasons: a hand-rolled payload that never thought about
 * provenance fails immediately, and — through {@code
 * basetool_ingest_client_rejected_total{reason="bad_provenance"}} — a producer drifting away from
 * the registered client becomes visible instead of blending in. The load-bearing controls remain
 * the token-level gates and the fact that the ingest path persists nothing (REQ-INGEST-004).
 *
 * <p>Inert until {@code allowed-tools} is configured, and reduced to log-and-count while {@code
 * audit-only} is set — the same rollout discipline every other gate here follows, so an operator
 * can measure the real client population before enforcing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProvenanceGuard {

  /** Cap on logged client-supplied provenance, so a padded field cannot bloat a log line. */
  private static final int MAX_LOGGED_PROVENANCE = 60;

  private final ClientIdentityProperties clientIdentityProperties;
  private final MeterRegistry meterRegistry;

  /**
   * Rejects a payload whose declared {@code tool} is absent from the configured allowlist.
   *
   * @param provenance the payload's self-declared provenance
   * @throws ClientNotAllowedException when the tool is not approved and enforcement is active
   */
  public void requireApprovedTool(@NotNull Provenance provenance) {
    if (clientIdentityProperties.getAllowedTools().isEmpty()) {
      return;
    }
    String tool = provenance.tool();
    if (tool != null && clientIdentityProperties.getAllowedTools().contains(tool)) {
      return;
    }
    meterRegistry
        .counter(
            MetricNames.INGEST_CLIENT_REJECTED,
            MetricNames.TAG_REASON,
            MetricNames.REASON_BAD_PROVENANCE)
        .increment();
    if (clientIdentityProperties.isAuditOnly()) {
      log.warn(
          "Ingest payload provenance would be rejected (audit-only): tool={}, toolVersion={}",
          LogSafe.text(tool, MAX_LOGGED_PROVENANCE),
          LogSafe.text(provenance.toolVersion(), MAX_LOGGED_PROVENANCE));
      return;
    }
    // WARN with the declared values: this is the one reject reason a caller fully controls, so the
    // actual string is the entire diagnostic value — it separates "an old extractor build emits a
    // legacy tool name" from "someone is hand-building payloads". LogSafe first: the fields are
    // unvalidated internet-facing free text and could otherwise forge a second log line.
    log.warn(
        "Ingest payload provenance rejected: tool={}, toolVersion={}, schemaVersion={}",
        LogSafe.text(tool, MAX_LOGGED_PROVENANCE),
        LogSafe.text(provenance.toolVersion(), MAX_LOGGED_PROVENANCE),
        provenance.schemaVersion());
    throw new ClientNotAllowedException(
        "This client is not approved for the basetool ingest path. Only the official basetool"
            + " SC extractor is supported; other tools are not permitted.");
  }
}
