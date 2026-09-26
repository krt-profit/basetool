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

import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.LinkedHashMap;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SpringDoc customizer adding the {@code 304 Not Modified} response and the {@code ETag} / {@code
 * Cache-Control} headers of {@link ETagConfig#shallowEtagFilter} to every {@code GET} operation.
 */
@Configuration
public class OpenApiCachingConfig {

  /**
   * Returns OpenAPI customizer that injects the {@code 304}/{@code ETag}/{@code Cache-Control}
   * metadata on every {@code GET} operation.
   *
   * @return OpenAPI customizer that injects the {@code 304}/{@code ETag}/{@code Cache-Control}
   *     metadata on every {@code GET} operation
   */
  @Bean
  public OpenApiCustomizer cachingOpenApiCustomizer() {
    return openApi ->
        openApi
            .getPaths()
            .forEach(
                (path, item) -> {
                  item.readOperationsMap()
                      .forEach(
                          (httpMethod, operation) -> {
                            if (httpMethod.name().equalsIgnoreCase("GET")) {
                              ApiResponses responses = operation.getResponses();
                              responses.addApiResponse(
                                  "304",
                                  new ApiResponse()
                                      .description("Not Modified (ETag/If-None-Match)"));

                              ApiResponse ok =
                                  responses.computeIfAbsent(
                                      "200", k -> new ApiResponse().description("OK"));
                              if (ok.getHeaders() == null) {
                                ok.setHeaders(new LinkedHashMap<>());
                              }
                              ok.getHeaders()
                                  .putIfAbsent(
                                      "ETag",
                                      new Header()
                                          .description("Entity Tag for conditional requests")
                                          .schema(new StringSchema()));
                              ok.getHeaders()
                                  .putIfAbsent(
                                      "Cache-Control",
                                      new Header()
                                          .description(
                                              "Caching policy (e.g. no-cache for API responses)")
                                          .schema(new StringSchema()));
                            }
                          });
                });
  }
}
