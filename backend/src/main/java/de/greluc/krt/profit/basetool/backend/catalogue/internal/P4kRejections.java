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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.CatalogueMetricNames;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kRejectionReason;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Records every refused catalogue: one log line and one increment of {@code
 * basetool_p4k_import_rejected_total{reason}} (REQ-P4K-001, REQ-P4K-004).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class P4kRejections {

  private final MeterRegistry meterRegistry;

  /**
   * Registers one series per refusal reason at zero, so the first refusal after a start is an
   * increase the {@code P4kImportChannelRejected} alert can see (REQ-OBS-022).
   */
  @PostConstruct
  void registerAtZero() {
    for (P4kRejectionReason reason : P4kRejectionReason.values()) {
      meterRegistry.counter(
          CatalogueMetricNames.P4K_IMPORT_REJECTED, MetricNames.TAG_REASON, reason.tagValue());
    }
  }

  /**
   * Logs and counts one refusal.
   *
   * @param reason why the catalogue was refused
   */
  public void record(@NotNull P4kRejectionReason reason) {
    log.warn("P4K import refused a catalogue: reason={}", reason.tagValue());
    meterRegistry
        .counter(
            CatalogueMetricNames.P4K_IMPORT_REJECTED, MetricNames.TAG_REASON, reason.tagValue())
        .increment();
  }
}
