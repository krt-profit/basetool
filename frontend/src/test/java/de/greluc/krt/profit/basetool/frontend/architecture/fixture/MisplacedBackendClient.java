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

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;

/**
 * A planted violation for {@code TypedBackendClientTest}: a typed client outside any {@code
 * <domain>.client} package that takes a {@code URI} and caches a response. Never instantiated and
 * deliberately not a Spring bean.
 */
@RequiredArgsConstructor
public class MisplacedBackendClient {

  private final BackendApiClient backendApiClient;

  /**
   * Reads whatever the caller points it at.
   *
   * @param target the request URL
   * @return the body
   */
  @Cacheable("rogue")
  public String read(URI target) {
    return backendApiClient.get(target.toString(), String.class);
  }
}
