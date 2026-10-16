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

package de.greluc.krt.profit.basetool.ingest.observability;

import io.micrometer.common.KeyValue;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.client.observation.DefaultClientRequestObservationConvention;

/**
 * Derives the {@code uri} key value of {@code http.client.requests} already scrubbed by {@link
 * ObservationPrivacyFilter#scrub}, so the {@code http.client.requests.active} long-task timer,
 * which is tagged at observation start before any {@code ObservationFilter} runs, carries the same
 * bounded value as the timer (REQ-OBS-006).
 */
public class ScrubbedClientRequestObservationConvention
    extends DefaultClientRequestObservationConvention {

  /**
   * Returns the default {@code uri} key value with its query string cut and its UUID and numeric
   * path segments collapsed to {@code {id}}.
   *
   * @param context the observation context of the outbound request
   * @return the scrubbed {@code uri} key value, or the default one when nothing needed scrubbing
   */
  @Override
  protected @NotNull KeyValue uri(@NotNull ClientRequestObservationContext context) {
    KeyValue uri = super.uri(context);
    String scrubbed = ObservationPrivacyFilter.scrub(uri.getValue(), true);
    return scrubbed.equals(uri.getValue()) ? uri : KeyValue.of(uri.getKey(), scrubbed);
  }
}
