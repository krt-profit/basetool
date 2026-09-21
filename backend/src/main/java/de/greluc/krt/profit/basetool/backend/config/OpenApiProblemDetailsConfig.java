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

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Documents the project's RFC&nbsp;7807 problem-detail responses in the generated OpenAPI spec.
 *
 * <p>Every endpoint can produce a 400/401/403/404/409/500 with {@code application/problem+json}
 * (the contract enforced by {@link
 * de.greluc.krt.profit.basetool.backend.exception.GlobalExceptionHandler}). SpringDoc cannot infer
 * that from controller signatures alone, so this customizer attaches the {@code ProblemDetail}
 * schema reference to every operation's response map and registers the schema itself in the
 * components section so the spec lints cleanly.
 */
@Configuration
public class OpenApiProblemDetailsConfig {

  /**
   * Returns SpringDoc customizer that decorates every operation with the standard error responses
   * ({@code 400/401/403/404/409/500}) and ensures the {@code ProblemDetail} schema is present.
   *
   * @return SpringDoc customizer that decorates every operation with the standard error responses
   *     ({@code 400/401/403/404/409/500}) and ensures the {@code ProblemDetail} schema is present
   */
  @Bean
  public OpenApiCustomizer problemDetailsCustomizer() {
    return this::customizeOpenApi;
  }

  private void customizeOpenApi(OpenAPI openApi) {
    ensureProblemDetailSchema(openApi);
    if (openApi.getPaths() == null) {
      return;
    }
    openApi
        .getPaths()
        .values()
        .forEach(
            pathItem -> {
              pathItem
                  .readOperations()
                  .forEach(
                      operation -> {
                        ApiResponses responses = operation.getResponses();
                        if (responses == null) {
                          return;
                        }
                        addProblemResponse(responses, "400", "Bad Request");
                        addProblemResponse(responses, "401", "Unauthorized");
                        addProblemResponse(responses, "403", "Forbidden");
                        addProblemResponse(responses, "404", "Not Found");
                        addProblemResponse(responses, "409", "Conflict");
                        addProblemResponse(responses, "500", "Internal Server Error");
                      });
            });
  }

  private void addProblemResponse(ApiResponses responses, String code, String description) {
    Content content =
        new Content()
            .addMediaType(
                "application/problem+json",
                new MediaType().schema(new Schema<>().$ref("#/components/schemas/ProblemDetail")));
    responses.addApiResponse(code, new ApiResponse().description(description).content(content));
  }

  private void ensureProblemDetailSchema(@NotNull OpenAPI openApi) {
    Components components = openApi.getComponents();
    if (components == null) {
      components = new Components();
      openApi.setComponents(components);
    }
    Map<String, Schema> schemas = components.getSchemas();
    if (schemas == null || !schemas.containsKey("ProblemDetail")) {
      @SuppressWarnings("rawtypes")
      Schema<?> problem =
          new Schema<>()
              .type("object")
              .addProperty("type", new Schema<String>().type("string").format("uri"))
              .addProperty("title", new Schema<String>().type("string"))
              .addProperty("status", new Schema<Integer>().type("integer").format("int32"))
              .addProperty("detail", new Schema<String>().type("string"))
              .addProperty("instance", new Schema<String>().type("string").format("uri"))
              .addProperty("errors", new Schema<Map<String, String>>().type("object"));
      components.addSchemas("ProblemDetail", problem);
    }
  }
}
