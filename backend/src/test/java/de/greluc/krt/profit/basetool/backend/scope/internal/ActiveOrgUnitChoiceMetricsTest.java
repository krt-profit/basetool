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

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.scope.api.ScopeMetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests of the active-org-unit choice counter (REQ-ORG-031, REQ-OBS-011). */
class ActiveOrgUnitChoiceMetricsTest {

  private final MeterRegistry registry = new SimpleMeterRegistry();
  private final ActiveOrgUnitChoiceMetrics metrics = new ActiveOrgUnitChoiceMetrics(registry);

  @Test
  @DisplayName("the counter exists at zero for both targets and carries no other label")
  void registersBothTargetsAtZero() {
    metrics.registerWriteCounters();

    assertThat(count(ScopeMetricNames.TARGET_UNIT)).isZero();
    assertThat(count(ScopeMetricNames.TARGET_ALL)).isZero();
    assertThat(registry.get(ScopeMetricNames.ACTIVE_ORG_UNIT_CHOICE_WRITES).counters())
        .hasSize(2)
        .allSatisfy(counter ->
            assertThat(counter.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactly(ScopeMetricNames.TAG_TARGET));
  }

  @Test
  @DisplayName("a choice of one unit counts as unit, whichever unit it is")
  void countsAUnitChoiceUnderUnit() {
    metrics.registerWriteCounters();

    metrics.recordWrite(UUID.randomUUID());
    metrics.recordWrite(UUID.randomUUID());

    assertThat(count(ScopeMetricNames.TARGET_UNIT)).isEqualTo(2);
    assertThat(count(ScopeMetricNames.TARGET_ALL)).isZero();
  }

  @Test
  @DisplayName("a choice of all units counts as all")
  void countsAnAllChoiceUnderAll() {
    metrics.registerWriteCounters();

    metrics.recordWrite(null);

    assertThat(count(ScopeMetricNames.TARGET_ALL)).isEqualTo(1);
    assertThat(count(ScopeMetricNames.TARGET_UNIT)).isZero();
  }

  private double count(String target) {
    return registry
        .get(ScopeMetricNames.ACTIVE_ORG_UNIT_CHOICE_WRITES)
        .tag(ScopeMetricNames.TAG_TARGET, target)
        .counter()
        .count();
  }
}
