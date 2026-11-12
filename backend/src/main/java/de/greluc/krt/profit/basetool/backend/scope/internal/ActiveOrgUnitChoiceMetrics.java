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

package de.greluc.krt.profit.basetool.backend.scope.internal;

import de.greluc.krt.profit.basetool.backend.scope.api.ScopeMetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Counts the changed choices of the active org unit as {@code
 * basetool_active_org_unit_choice_writes_total{target}} (REQ-ORG-031, REQ-OBS-011); the choice is
 * counted, not audited.
 */
@Component
@RequiredArgsConstructor
public class ActiveOrgUnitChoiceMetrics {

  private final MeterRegistry meterRegistry;

  /** Registers the counter for both targets at zero. */
  @PostConstruct
  void registerWriteCounters() {
    counter(ScopeMetricNames.TARGET_UNIT);
    counter(ScopeMetricNames.TARGET_ALL);
  }

  /**
   * Counts one stored change of the choice.
   *
   * @param orgUnitId the chosen org unit, or {@code null} for all of the member's org units
   */
  public void recordWrite(@Nullable UUID orgUnitId) {
    counter(orgUnitId == null ? ScopeMetricNames.TARGET_ALL : ScopeMetricNames.TARGET_UNIT)
        .increment();
  }

  private Counter counter(String target) {
    return meterRegistry.counter(
        ScopeMetricNames.ACTIVE_ORG_UNIT_CHOICE_WRITES, ScopeMetricNames.TAG_TARGET, target);
  }
}
