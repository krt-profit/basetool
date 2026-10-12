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

import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kImportJobStatus;
import de.greluc.krt.profit.basetool.backend.platform.api.BusinessGaugeSource;
import de.greluc.krt.profit.basetool.backend.platform.api.BusinessGauges;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The P4K import queue's business gauges: pending and running jobs and the age of the oldest
 * pending one (REQ-OBS-011).
 */
@Component
@RequiredArgsConstructor
public class P4kImportBusinessGauges implements BusinessGaugeSource {

  private final P4kImportJobRepository p4kImportJobRepository;

  private final AtomicLong p4kPending = new AtomicLong();
  private final AtomicLong p4kRunning = new AtomicLong();
  private final AtomicLong p4kOldestAge = new AtomicLong();

  @Override
  public void register(@NotNull MeterRegistry registry) {
    BusinessGauges.countGauge(
        registry,
        MetricNames.P4K_IMPORT_JOB_PENDING,
        p4kPending,
        P4kImportJobStatus.PENDING.name());
    BusinessGauges.countGauge(
        registry,
        MetricNames.P4K_IMPORT_JOB_PENDING,
        p4kRunning,
        P4kImportJobStatus.RUNNING.name());
    BusinessGauges.ageGauge(registry, MetricNames.P4K_IMPORT_JOB_PENDING_OLDEST_AGE, p4kOldestAge);
  }

  @Override
  public void sample() {
    p4kPending.set(p4kImportJobRepository.countByStatus(P4kImportJobStatus.PENDING));
    p4kRunning.set(p4kImportJobRepository.countByStatus(P4kImportJobStatus.RUNNING));
    p4kOldestAge.set(
        BusinessGauges.ageSeconds(
            p4kImportJobRepository.findOldestCreatedAtByStatus(P4kImportJobStatus.PENDING)));
  }
}
