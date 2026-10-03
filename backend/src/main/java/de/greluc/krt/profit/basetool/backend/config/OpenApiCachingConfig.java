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

import de.greluc.krt.profit.basetool.backend.filter.NoStoreApiScopes;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.LinkedHashMap;
import org.jetbrains.annotations.NotNull;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.PathContainer;

/**
 * SpringDoc customizer documenting the caching of every {@code GET} operation as the backend
 * applies it (REQ-SEC-031): the {@link NoStoreApiScopes} families answer {@code private, no-store}
 * without an {@code ETag}, every other operation carries the {@code ETag} of {@link
 * EtagConfig#shallowEtagFilter} and may answer {@code 304 Not Modified}.
 */
@Configuration
public class OpenApiCachingConfig {

  /** The {@code Cache-Control} value of a {@code no-store} family. */
  static final String NO_STORE = "private, no-store";

  /** The {@code Cache-Control} value of a revalidatable response. */
  static final String REVALIDATE = "no-cache, must-revalidate";

  /**
   * Returns the customizer that documents each {@code GET} operation's caching headers and, for a
   * revalidatable one, its {@code 304} response.
   *
   * @return the OpenAPI customizer
   */
  @Bean
  public OpenApiCustomizer cachingOpenApiCustomizer() {
    return openApi ->
        openApi
            .getPaths()
            .forEach(
                (path, item) ->
                    item.readOperationsMap()
                        .forEach(
                            (httpMethod, operation) -> {
                              if (httpMethod.name().equalsIgnoreCase("GET")) {
                                document(path, operation.getResponses());
                              }
                            }));
  }

  /**
   * Answers whether the responses of a documented path are {@code no-store}.
   *
   * @param path the path template as the document writes it
   * @return {@code true} when a {@link NoStoreApiScopes} family covers the path
   */
  static boolean isNoStore(@NotNull String path) {
    return NoStoreApiScopes.matches(PathContainer.parsePath(path.replaceAll("\\{[^}]+}", "x")));
  }

  /**
   * Adds the caching headers, and the {@code 304} response where an {@code ETag} is sent.
   *
   * @param path the path template
   * @param responses the operation's responses, changed in place
   */
  private static void document(@NotNull String path, @NotNull ApiResponses responses) {
    boolean noStore = isNoStore(path);
    ApiResponse ok = responses.computeIfAbsent("200", k -> new ApiResponse().description("OK"));
    if (ok.getHeaders() == null) {
      ok.setHeaders(new LinkedHashMap<>());
    }
    if (!noStore) {
      responses.addApiResponse(
          "304", new ApiResponse().description("Not Modified (ETag/If-None-Match)"));
      ok.getHeaders()
          .putIfAbsent(
              "ETag",
              new Header()
                  .description("Entity Tag for conditional requests")
                  .schema(new StringSchema()));
    }
    ok.getHeaders()
        .putIfAbsent(
            "Cache-Control",
            new Header()
                .description("Caching policy: " + (noStore ? NO_STORE : REVALIDATE))
                .schema(new StringSchema()));
  }
}
