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
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The navigation module's business gauge: how many members personalised each preference section
 * (REQ-OBS-011).
 */
@Component
public class NavigationBusinessGauges implements BusinessGaugeSource {

  private final Map<NavigationSection, LongSupplier> counts =
      new EnumMap<>(NavigationSection.class);

  private final Map<NavigationSection, AtomicLong> accounts =
      new EnumMap<>(NavigationSection.class);

  /**
   * Binds each section to the count of members that personalised it.
   *
   * @param repository the preferences store
   * @param appearances the „Darstellung" store
   */
  public NavigationBusinessGauges(
      NavigationPreferencesRepository repository, NavigationAppearanceRepository appearances) {
    counts.put(NavigationSection.FAVOURITES, repository::countWithFavourites);
    counts.put(NavigationSection.HIDDEN, repository::countWithHidden);
    counts.put(NavigationSection.ORDER, repository::countWithOrder);
    counts.put(NavigationSection.TAB_BAR, repository::countWithTabBar);
    counts.put(NavigationSection.APPEARANCE, appearances::countPersonalised);
    counts.keySet().forEach(section -> accounts.put(section, new AtomicLong()));
  }

  @Override
  public void register(@NotNull MeterRegistry registry) {
    accounts.forEach((section, value) ->
        Gauge.builder(NavigationMetrics.PERSONALISED_ACCOUNTS, value, AtomicLong::doubleValue)
            .tag(NavigationMetrics.TAG_SECTION, section.getTag())
            .register(registry));
  }

  @Override
  public void sample() {
    counts.forEach((section, count) -> accounts.get(section).set(count.getAsLong()));
  }
}
