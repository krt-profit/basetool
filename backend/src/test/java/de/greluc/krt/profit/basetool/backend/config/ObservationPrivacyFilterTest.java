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

package de.greluc.krt.profit.basetool.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the observation scrubbing (REQ-OBS-006/-009): query strings (user search text!)
 * never reach metric tags or span attributes, entity ids never explode the low-cardinality {@code
 * uri} tag, and non-URL key-values pass through untouched.
 */
class ObservationPrivacyFilterTest {

  private final ObservationPrivacyFilter filter = new ObservationPrivacyFilter();

  @Test
  void shouldCutQueryStringFromUriTagAndHttpUrlAttribute() {
    // Given
    Observation.Context context = new Observation.Context();
    context.addLowCardinalityKeyValue(KeyValue.of("uri", "/api/v1/users/search?username=hans"));
    context.addHighCardinalityKeyValue(
        KeyValue.of("http.url", "/api/v1/users/search?username=hans"));

    // When
    filter.map(context);

    // Then
    assertThat(context.getLowCardinalityKeyValue("uri").getValue())
        .isEqualTo("/api/v1/users/search");
    assertThat(context.getHighCardinalityKeyValue("http.url").getValue())
        .isEqualTo("/api/v1/users/search");
  }

  @Test
  void shouldCollapseUuidAndNumericSegmentsInUriTagOnly() {
    // Given
    Observation.Context context = new Observation.Context();
    context.addLowCardinalityKeyValue(
        KeyValue.of("uri", "/api/v1/locations/3f2a8c1e-0d4b-4f6a-9c3e-1b2d3f4a5b6c/items/42"));
    context.addHighCardinalityKeyValue(
        KeyValue.of("http.url", "/api/v1/locations/3f2a8c1e-0d4b-4f6a-9c3e-1b2d3f4a5b6c"));

    // When
    filter.map(context);

    // Then: the metric tag is bounded, the trace attribute keeps the raw path (ids only).
    assertThat(context.getLowCardinalityKeyValue("uri").getValue())
        .isEqualTo("/api/v1/locations/{id}/items/{id}");
    assertThat(context.getHighCardinalityKeyValue("http.url").getValue())
        .isEqualTo("/api/v1/locations/3f2a8c1e-0d4b-4f6a-9c3e-1b2d3f4a5b6c");
  }

  @Test
  void shouldLeaveTemplatedUriAndOtherKeyValuesUntouched() {
    // Given
    Observation.Context context = new Observation.Context();
    context.addLowCardinalityKeyValue(KeyValue.of("uri", "/api/v1/locations/{id}"));
    context.addLowCardinalityKeyValue(KeyValue.of("outcome", "SUCCESS"));
    context.addLowCardinalityKeyValue(KeyValue.of("exception", "What?Ever"));

    // When
    filter.map(context);

    // Then: route templates stay intact and non-URL keys are never modified.
    assertThat(context.getLowCardinalityKeyValue("uri").getValue())
        .isEqualTo("/api/v1/locations/{id}");
    assertThat(context.getLowCardinalityKeyValue("outcome").getValue()).isEqualTo("SUCCESS");
    assertThat(context.getLowCardinalityKeyValue("exception").getValue()).isEqualTo("What?Ever");
  }
}
