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

package de.greluc.krt.profit.basetool.backend.purchasing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import de.greluc.krt.profit.basetool.backend.purchasing.api.DealStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The purchasing business gauges: deals per state, overdue settlements, pause, delegates. */
@ExtendWith(MockitoExtension.class)
class PurchasingBusinessGaugesTest {

  @Mock private PurchasingDealRepository dealRepository;
  @Mock private PurchasingDelegateRepository delegateRepository;
  @Mock private PurchasingSettingRepository settingRepository;

  private SimpleMeterRegistry registry;
  private PurchasingBusinessGauges gauges;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    gauges = new PurchasingBusinessGauges(dealRepository, delegateRepository, settingRepository);
    gauges.register(registry);
  }

  @Test
  void registeringCreatesOneGaugePerDealStateAndTheSingleValueGaugesAtZero() {
    for (DealStatus status : DealStatus.values()) {
      assertThat(dealsIn(status)).isZero();
    }
    assertThat(value(MetricNames.PURCHASING_SETTLEMENTS_OVERDUE)).isZero();
    assertThat(value(MetricNames.PURCHASING_PAUSED)).isZero();
    assertThat(value(MetricNames.PURCHASING_DELEGATES_ACTIVE)).isZero();
  }

  @Test
  void samplingReadsTheCountsFromTheRepositories() {
    when(dealRepository.countByStatus(any(DealStatus.class)))
        .thenAnswer(invocation -> ((DealStatus) invocation.getArgument(0)).ordinal() + 1L);
    when(dealRepository.countOverdue(any(Instant.class))).thenReturn(3L);
    when(delegateRepository.countActive(any(Instant.class))).thenReturn(2L);
    when(settingRepository.findById(PurchasingSetting.SINGLETON_ID))
        .thenReturn(Optional.of(setting(true)));

    gauges.sample();

    for (DealStatus status : DealStatus.values()) {
      assertThat(dealsIn(status)).isEqualTo(status.ordinal() + 1.0);
    }
    assertThat(value(MetricNames.PURCHASING_SETTLEMENTS_OVERDUE)).isEqualTo(3.0);
    assertThat(value(MetricNames.PURCHASING_DELEGATES_ACTIVE)).isEqualTo(2.0);
    assertThat(value(MetricNames.PURCHASING_PAUSED)).isEqualTo(1.0);
  }

  @Test
  void thePauseGaugeFallsBackToZeroWhenPurchasingIsRunning() {
    when(settingRepository.findById(PurchasingSetting.SINGLETON_ID))
        .thenReturn(Optional.of(setting(true)));
    gauges.sample();
    assertThat(value(MetricNames.PURCHASING_PAUSED)).isEqualTo(1.0);

    when(settingRepository.findById(PurchasingSetting.SINGLETON_ID))
        .thenReturn(Optional.of(setting(false)));
    gauges.sample();
    assertThat(value(MetricNames.PURCHASING_PAUSED)).isZero();

    when(settingRepository.findById(PurchasingSetting.SINGLETON_ID)).thenReturn(Optional.empty());
    gauges.sample();
    assertThat(value(MetricNames.PURCHASING_PAUSED)).isZero();
  }

  private double dealsIn(DealStatus status) {
    return registry
        .get(MetricNames.PURCHASING_DEALS_BY_STATUS)
        .tag(MetricNames.TAG_STATUS, status.name())
        .gauge()
        .value();
  }

  private double value(String name) {
    return registry.get(name).gauge().value();
  }

  private static PurchasingSetting setting(boolean paused) {
    PurchasingSetting setting = new PurchasingSetting();
    setting.setId(PurchasingSetting.SINGLETON_ID);
    setting.setPaused(paused);
    return setting;
  }
}
