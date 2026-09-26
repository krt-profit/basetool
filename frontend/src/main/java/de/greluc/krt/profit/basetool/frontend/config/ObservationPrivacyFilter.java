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

package de.greluc.krt.profit.basetool.frontend.config;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Scrubs the {@code uri} and {@code http.url} observation key-values before they become metric tags
 * or span attributes (REQ-OBS-006).
 *
 * <p>Cuts query strings from both and collapses UUID and numeric path segments in {@code uri} to
 * {@code {id}}; all other key-values pass through unchanged.
 */
@Component
public class ObservationPrivacyFilter implements ObservationFilter {

  /** Path segment shaped like a UUID — an entity id, never a route literal. */
  private static final Pattern UUID_SEGMENT =
      Pattern.compile(
          "/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  /** Purely numeric path segment — an entity id, never a route literal. */
  private static final Pattern NUMERIC_SEGMENT = Pattern.compile("/\\d+(?=/|$)");

  @Override
  public Observation.Context map(Observation.Context context) {
    List<KeyValue> lowReplacements = new ArrayList<>();
    for (KeyValue kv : context.getLowCardinalityKeyValues()) {
      if (isUrlKey(kv.getKey())) {
        String scrubbed = scrub(kv.getValue(), true);
        if (!scrubbed.equals(kv.getValue())) {
          lowReplacements.add(KeyValue.of(kv.getKey(), scrubbed));
        }
      }
    }
    lowReplacements.forEach(context::addLowCardinalityKeyValue);

    List<KeyValue> highReplacements = new ArrayList<>();
    for (KeyValue kv : context.getHighCardinalityKeyValues()) {
      if (isUrlKey(kv.getKey())) {
        String scrubbed = scrub(kv.getValue(), false);
        if (!scrubbed.equals(kv.getValue())) {
          highReplacements.add(KeyValue.of(kv.getKey(), scrubbed));
        }
      }
    }
    highReplacements.forEach(context::addHighCardinalityKeyValue);
    return context;
  }

  /**
   * Whether the key-value carries a request target that needs scrubbing.
   *
   * @param key the observation key-value key
   * @return {@code true} for the {@code uri} tag and the {@code http.url} attribute
   */
  private static boolean isUrlKey(String key) {
    return "uri".equals(key) || "http.url".equals(key);
  }

  /**
   * Cuts the query string and optionally collapses UUID and numeric path segments to {@code {id}}.
   *
   * @param value the raw key-value content; may be {@code null}
   * @param normalizeIds {@code true} for the {@code uri} tag, {@code false} for {@code http.url}
   * @return the scrubbed value, or the input unchanged when nothing matched
   */
  static String scrub(String value, boolean normalizeIds) {
    if (value == null || value.isEmpty()) {
      return value;
    }
    String result = value;
    int queryStart = result.indexOf('?');
    if (queryStart >= 0) {
      result = result.substring(0, queryStart);
    }
    if (normalizeIds) {
      result = UUID_SEGMENT.matcher(result).replaceAll("/{id}");
      result = NUMERIC_SEGMENT.matcher(result).replaceAll("/{id}");
    }
    return result;
  }
}
