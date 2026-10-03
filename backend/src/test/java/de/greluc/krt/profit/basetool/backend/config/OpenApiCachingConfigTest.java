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

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.Test;

/** Pins which documented {@code GET} operations carry the {@code ETag} and {@code 304} response. */
class OpenApiCachingConfigTest {

  /**
   * Builds a document with one {@code GET} per path and runs the customizer over it.
   *
   * @param paths the documented path templates
   * @return the customized document
   */
  private static OpenAPI customized(String... paths) {
    Paths documented = new Paths();
    for (String path : paths) {
      documented.addPathItem(
          path,
          new PathItem()
              .get(
                  new Operation()
                      .responses(new ApiResponses().addApiResponse("200", new ApiResponse()))));
    }
    OpenAPI openApi = new OpenAPI().paths(documented);
    new OpenApiCachingConfig().cachingOpenApiCustomizer().customise(openApi);
    return openApi;
  }

  @Test
  void aRevalidatableFamilyDocumentsTheEtagAndThe304() {
    ApiResponses responses =
        customized("/api/v1/cities/{id}")
            .getPaths()
            .get("/api/v1/cities/{id}")
            .getGet()
            .getResponses();

    assertThat(responses).containsKey("304");
    assertThat(responses.get("200").getHeaders()).containsKeys("ETag", "Cache-Control");
    assertThat(responses.get("200").getHeaders().get("Cache-Control").getDescription())
        .contains(OpenApiCachingConfig.REVALIDATE);
  }

  @Test
  void aNoStoreFamilyDocumentsNeitherTheEtagNorThe304() {
    for (PathItem item :
        customized("/api/v1/me/layout", "/api/v1/bank/accounts/{id}").getPaths().values()) {
      ApiResponses responses = item.getGet().getResponses();
      assertThat(responses).doesNotContainKey("304");
      assertThat(responses.get("200").getHeaders()).doesNotContainKey("ETag");
      assertThat(responses.get("200").getHeaders().get("Cache-Control").getDescription())
          .contains(OpenApiCachingConfig.NO_STORE);
    }
  }

  @Test
  void thePathTemplateIsClassifiedLikeARequestPath() {
    assertThat(OpenApiCachingConfig.isNoStore("/api/v1/squadrons/{id}/members")).isTrue();
    assertThat(OpenApiCachingConfig.isNoStore("/api/v1/cities/{id}")).isFalse();
    assertThat(OpenApiCachingConfig.isNoStore("/api/v1/exchange/catalog/locations")).isFalse();
  }
}
