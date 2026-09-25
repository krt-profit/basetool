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
import de.greluc.krt.profit.basetool.ingest.metrics.MetricNames;
import de.greluc.krt.profit.basetool.ingest.model.dto.Provenance;
import de.greluc.krt.profit.basetool.ingest.web.ClientNotAllowedException;
import de.greluc.krt.profit.basetool.logging.LogSafe;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Checks the payload's self-declared producer against {@code
 * app.ingest.client-identity.allowed-tools} (REQ-INGEST-011).
 *
 * <p>The {@code tool} field is client-supplied and is not authentication. The guard is inert until
 * the allowlist is configured and only logs and counts while {@code audit-only} is set.
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
    if (clientIdentityProperties.allowedTools().isEmpty()) {
      return;
    }
    String tool = provenance.tool();
    if (tool != null && isAllowed(tool)) {
      return;
    }
    meterRegistry
        .counter(
            MetricNames.INGEST_CLIENT_REJECTED,
            MetricNames.TAG_REASON,
            MetricNames.REASON_BAD_PROVENANCE)
        .increment();
    if (clientIdentityProperties.auditOnly()) {
      log.warn(
          "Ingest payload provenance would be rejected (audit-only): tool={}, toolVersion={}",
          LogSafe.text(tool, MAX_LOGGED_PROVENANCE),
          LogSafe.text(provenance.toolVersion(), MAX_LOGGED_PROVENANCE));
      return;
    }
    log.warn(
        "Ingest payload provenance rejected: tool={}, toolVersion={}, schemaVersion={}",
        LogSafe.text(tool, MAX_LOGGED_PROVENANCE),
        LogSafe.text(provenance.toolVersion(), MAX_LOGGED_PROVENANCE),
        provenance.schemaVersion());
    throw new ClientNotAllowedException(
        "This client is not approved for the basetool ingest path (payload provenance). Only the"
            + " official basetool SC extractor is supported; other tools are not permitted.");
  }

  /**
   * Reports whether the declared producer matches an allowlist entry, ignoring case under {@link
   * Locale#ROOT}.
   *
   * @param tool the payload's declared, non-null producer
   * @return {@code true} when an allowlist entry matches ignoring case
   */
  private boolean isAllowed(@NotNull String tool) {
    for (String allowed : clientIdentityProperties.allowedTools()) {
      if (allowed.toLowerCase(Locale.ROOT).equals(tool.toLowerCase(Locale.ROOT))) {
        return true;
      }
    }
    return false;
  }
}
