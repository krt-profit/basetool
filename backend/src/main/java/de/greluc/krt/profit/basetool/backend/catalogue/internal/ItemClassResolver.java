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
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipComponentClass;
import de.greluc.krt.profit.basetool.logging.LogSafe;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Resolves an item's class value from the SC Wiki or the P4K to a {@link ShipComponentClass},
 * counting and logging an unknown one (REQ-P4K-019, REQ-OBS-023).
 */
@Slf4j
@Component
public class ItemClassResolver {

  /** The longest class value the log quotes. */
  private static final int MAX_CLASS_LOG_LENGTH = 64;

  /** The most distinct unknown values the log names, a bound on the log-once set. */
  private static final int MAX_LOGGED_UNKNOWN_CLASSES = 32;

  /** The unknown values already logged, lower-cased. */
  private final Set<String> unknownClassesLogged = ConcurrentHashMap.newKeySet();

  /** {@code basetool_catalogue_item_class_unknown_total}. */
  private final Counter unknownClasses;

  /**
   * Registers the unknown-class counter, so it exists before the first increment.
   *
   * @param meterRegistry the registry the counter lives in
   */
  public ItemClassResolver(@NotNull MeterRegistry meterRegistry) {
    unknownClasses = meterRegistry.counter(CatalogueMetricNames.CATALOGUE_ITEM_CLASS_UNKNOWN);
  }

  /**
   * Resolves a non-blank class value; an unknown one bumps the counter and is logged once.
   *
   * @param value the source's class value
   * @param source the source's name for the log line, e.g. {@code SC Wiki}
   * @return the class, or {@code null} when the value is unknown
   */
  @Nullable
  public ShipComponentClass resolve(@NotNull String value, @NotNull String source) {
    ShipComponentClass resolved = ShipComponentClass.fromWikiClass(value);
    if (resolved == null) {
      unknownClasses.increment();
      String logged = LogSafe.text(value.strip(), MAX_CLASS_LOG_LENGTH);
      if (unknownClassesLogged.size() < MAX_LOGGED_UNKNOWN_CLASSES
          && unknownClassesLogged.add(logged.toLowerCase(Locale.ROOT))) {
        log.warn(
            "Unknown {} item class '{}' — storing no class; add it to ShipComponentClass.",
            source,
            logged);
      }
    }
    return resolved;
  }
}
