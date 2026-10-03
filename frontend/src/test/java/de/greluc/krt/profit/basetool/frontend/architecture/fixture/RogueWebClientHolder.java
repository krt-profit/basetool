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

package de.greluc.krt.profit.basetool.frontend.architecture.fixture;

import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * A planted violation for {@code WebClientConfinementTest}: holds, builds and wraps its own {@link
 * WebClient} outside the kernel. Never instantiated.
 */
public class RogueWebClientHolder {

  private final WebClient client = WebClient.builder().baseUrl("https://backend:11261").build();

  /**
   * Creates an HTTP-interface client over the rogue client.
   *
   * @param type the client interface
   * @param <T> the client type
   * @return the proxy
   */
  public <T> T typedClient(Class<T> type) {
    return HttpServiceProxyFactory.builderFor(WebClientAdapter.create(client))
        .build()
        .createClient(type);
  }
}
