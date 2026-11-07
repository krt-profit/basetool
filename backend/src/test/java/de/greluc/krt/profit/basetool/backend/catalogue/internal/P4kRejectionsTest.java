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

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.catalogue.api.CatalogueMetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/** Every refusal reason's series exists at zero before the first refusal (REQ-OBS-022). */
class P4kRejectionsTest {

  @Test
  void everyReasonIsRegisteredAtZero() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();

    new P4kRejections(meters).registerAtZero();

    assertThat(meters.find(CatalogueMetricNames.P4K_IMPORT_REJECTED).counters())
        .extracting(c -> c.getId().getTag("reason"))
        .containsExactlyInAnyOrder(
            "channel", "schema_version", "build_missing", "older_build", "too_large");
    assertThat(meters.find(CatalogueMetricNames.P4K_IMPORT_REJECTED).counters())
        .extracting(Counter::count)
        .containsOnly(0.0d);
  }
}
