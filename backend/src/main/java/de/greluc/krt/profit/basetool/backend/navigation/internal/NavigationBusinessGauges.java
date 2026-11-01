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

package de.greluc.krt.profit.basetool.backend.navigation.internal;

import de.greluc.krt.profit.basetool.backend.platform.api.BusinessGaugeSource;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The navigation module's business gauge: how many members personalised each preference section
 * (REQ-OBS-011).
 */
@Component
@RequiredArgsConstructor
public class NavigationBusinessGauges implements BusinessGaugeSource {

  private final NavigationPreferencesRepository repository;

  private final AtomicLong favouriteAccounts = new AtomicLong();
  private final AtomicLong hiddenAccounts = new AtomicLong();
  private final AtomicLong orderAccounts = new AtomicLong();

  @Override
  public void register(@NotNull MeterRegistry registry) {
    Gauge.builder(
            NavigationMetrics.PERSONALISED_ACCOUNTS, favouriteAccounts, AtomicLong::doubleValue)
        .tag(NavigationMetrics.TAG_SECTION, NavigationSection.FAVOURITES.getTag())
        .register(registry);
    Gauge.builder(NavigationMetrics.PERSONALISED_ACCOUNTS, hiddenAccounts, AtomicLong::doubleValue)
        .tag(NavigationMetrics.TAG_SECTION, NavigationSection.HIDDEN.getTag())
        .register(registry);
    Gauge.builder(NavigationMetrics.PERSONALISED_ACCOUNTS, orderAccounts, AtomicLong::doubleValue)
        .tag(NavigationMetrics.TAG_SECTION, NavigationSection.ORDER.getTag())
        .register(registry);
  }

  @Override
  public void sample() {
    favouriteAccounts.set(repository.countWithFavourites());
    hiddenAccounts.set(repository.countWithHidden());
    orderAccounts.set(repository.countWithOrder());
  }
}
