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

package de.greluc.krt.profit.basetool.frontend.kernel.backend;

import java.nio.charset.StandardCharsets;
import org.jetbrains.annotations.Nullable;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/** Retains a known client-refusal status independently of error-body consumption. */
final class BackendResponseStatus {

  static final String ATTRIBUTE = BackendResponseStatus.class.getName();

  private volatile @Nullable WebClientResponseException refusal;

  void observe(ClientResponse response) {
    refusal =
        response.statusCode().is4xxClientError()
            ? WebClientResponseException.create(
                response.statusCode().value(),
                response.statusCode().toString(),
                response.headers().asHttpHeaders(),
                new byte[0],
                StandardCharsets.UTF_8)
            : null;
  }

  @Nullable
  WebClientResponseException refusal() {
    return refusal;
  }

  static ExchangeFilterFunction observer() {
    return (request, next) ->
        next.exchange(request)
            .doOnNext(response -> {
              if (request.attributes().get(ATTRIBUTE) instanceof BackendResponseStatus status) {
                status.observe(response);
              }
            });
  }
}
