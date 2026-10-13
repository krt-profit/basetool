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

package de.greluc.krt.profit.basetool.backend.privacy.internal;

import de.greluc.krt.profit.basetool.backend.platform.api.BusinessGaugeSource;
import de.greluc.krt.profit.basetool.backend.platform.api.BusinessGauges;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import de.greluc.krt.profit.basetool.backend.privacy.api.DeletionRequestRepository;
import de.greluc.krt.profit.basetool.backend.privacy.api.DeletionRequestStatus;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The deletion-request queue's business gauges: pending requests and the age of the oldest
 * (REQ-OBS-011).
 */
@Component
@RequiredArgsConstructor
public class DeletionRequestBusinessGauges implements BusinessGaugeSource {

  private final DeletionRequestRepository deletionRequestRepository;

  private final AtomicLong deletionRequestPending = new AtomicLong();
  private final AtomicLong deletionRequestOldestAge = new AtomicLong();

  @Override
  public void register(@NotNull MeterRegistry registry) {
    BusinessGauges.countGauge(
        registry, MetricNames.DELETION_REQUEST_PENDING, deletionRequestPending);
    BusinessGauges.ageGauge(
        registry, MetricNames.DELETION_REQUEST_PENDING_OLDEST_AGE, deletionRequestOldestAge);
  }

  @Override
  public void sample() {
    deletionRequestPending.set(
        deletionRequestRepository.countByStatus(DeletionRequestStatus.PENDING));
    deletionRequestOldestAge.set(
        BusinessGauges.ageSeconds(
            deletionRequestRepository.findOldestCreatedAtByStatus(DeletionRequestStatus.PENDING)));
  }
}
