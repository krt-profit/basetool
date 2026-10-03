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

import de.greluc.krt.profit.basetool.backend.exception.CoreProblemCode;
import de.greluc.krt.profit.basetool.backend.exception.ProblemCode;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SpringDoc customizer that documents the RFC&nbsp;7807 {@code application/problem+json} error
 * responses of every operation and registers the {@code ProblemDetail} schema (REQ-API-004,
 * REQ-API-019).
 */
@Configuration
public class OpenApiProblemDetailsConfig {

  /** The schema every problem response refers to. */
  public static final String PROBLEM_SCHEMA = "ProblemDetail";

  /** The extension of the {@code code} property that lists every registered code. */
  public static final String CODES_EXTENSION = "x-problem-codes";

  /** The path prefix both rate limiters guard, so every operation under it can answer 429. */
  private static final String RATE_LIMITED_PREFIX = "/api/";

  /** Where the shared header definitions live in the document. */
  private static final String HEADER_REF_PREFIX = "#/components/headers/";

  /** The headers every 429 carries, name to description, in document order. */
  private static final SequencedMap<String, String> RATE_LIMIT_HEADERS = rateLimitHeaders();

  /**
   * Returns SpringDoc customizer that decorates every operation with the standard error responses
   * ({@code 400/401/403/404/409/500}, and {@code 429} under {@code /api/}) and registers the {@code
   * ProblemDetail} schema.
   *
   * @return SpringDoc customizer that decorates every operation with the standard error responses
   *     and registers the {@code ProblemDetail} schema
   */
  @Bean
  public OpenApiCustomizer problemDetailsCustomizer() {
    return OpenApiProblemDetailsConfig::customizeOpenApi;
  }

  /**
   * Registers the schema and adds the problem responses to every operation.
   *
   * @param openApi the document under construction
   */
  private static void customizeOpenApi(@NotNull OpenAPI openApi) {
    registerProblemDetailSchema(openApi);
    registerRateLimitHeaders(openApi);
    if (openApi.getPaths() == null) {
      return;
    }
    openApi
        .getPaths()
        .forEach(
            (path, pathItem) ->
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
                          if (path.startsWith(RATE_LIMITED_PREFIX)) {
                            addProblemResponse(
                                responses,
                                "429",
                                "Too Many Requests: a rate limit was exceeded"
                                    + " (RATE_LIMIT_EXCEEDED); Retry-After says how many seconds"
                                    + " to wait");
                            RATE_LIMIT_HEADERS
                                .keySet()
                                .forEach(
                                    name ->
                                        responses
                                            .get("429")
                                            .addHeaderObject(
                                                name, new Header().$ref(HEADER_REF_PREFIX + name)));
                          }
                          addProblemResponse(responses, "500", "Internal Server Error");
                        }));
  }

  /**
   * Lists the headers every 429 carries.
   *
   * @return header name to description, in document order
   */
  @NotNull
  private static SequencedMap<String, String> rateLimitHeaders() {
    SequencedMap<String, String> headers = new LinkedHashMap<>();
    headers.put("Retry-After", "Whole seconds to wait before the next call.");
    headers.put("X-Rate-Limit-Limit", "The capacity of the budget that refused the call.");
    headers.put("X-Rate-Limit-Remaining", "The tokens left in that budget; 0 on a refusal.");
    headers.put("X-Rate-Limit-Retry-After-Seconds", "The same wait as Retry-After.");
    return Collections.unmodifiableSequencedMap(headers);
  }

  /**
   * Registers the rate-limit headers under {@code components.headers}, each an integer.
   *
   * @param openApi the document under construction
   */
  private static void registerRateLimitHeaders(@NotNull OpenAPI openApi) {
    Components components = openApi.getComponents();
    if (components == null) {
      components = new Components();
      openApi.setComponents(components);
    }
    for (Map.Entry<String, String> entry : RATE_LIMIT_HEADERS.entrySet()) {
      components.addHeaders(
          entry.getKey(),
          new Header().description(entry.getValue()).schema(typed("integer", "int64", null)));
    }
  }

  /**
   * Adds one problem response, replacing whatever the operation documented for that status.
   *
   * @param responses the operation's responses
   * @param code the status code
   * @param description the response description
   */
  private static void addProblemResponse(
      @NotNull ApiResponses responses, @NotNull String code, @NotNull String description) {
    Content content =
        new Content()
            .addMediaType(
                "application/problem+json",
                new MediaType()
                    .schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM_SCHEMA)));
    responses.addApiResponse(code, new ApiResponse().description(description).content(content));
  }

  /**
   * Registers the {@code ProblemDetail} schema with the body's contract fields: the RFC 7807
   * members, {@code code} with the registered values, {@code correlationId}, {@code errors} and
   * {@code fieldErrors}.
   *
   * @param openApi the document under construction
   */
  private static void registerProblemDetailSchema(@NotNull OpenAPI openApi) {
    Components components = openApi.getComponents();
    if (components == null) {
      components = new Components();
      openApi.setComponents(components);
    }
    List<String> codes = Arrays.stream(CoreProblemCode.values()).map(ProblemCode::code).toList();

    Schema<Object> fieldError = typed("object", null, null);
    fieldError.addProperty("field", typed("string", null, "The field or parameter path."));
    fieldError.addProperty("message", typed("string", null, "The localized message."));

    Schema<Object> code =
        typed(
            "string",
            null,
            "The stable reason, one of the values in x-problem-codes (registry CoreProblemCode,"
                + " REQ-API-019). The exchange operations under /api/v1/exchange answer the codes"
                + " of the frozen exchange contract instead (ExchangeProblemException). The list"
                + " grows; a client treats an unknown code by its status.");
    code.addExtension(CODES_EXTENSION, codes);

    Schema<Object> errors =
        typed("object", null, "Validation failures as field to message; see fieldErrors.");
    errors.setAdditionalProperties(typed("string", null, null));

    Schema<Object> fieldErrors =
        typed(
            "array",
            null,
            "Validation failures, one per field or parameter (VALIDATION_FAILED,"
                + " CONSTRAINT_VIOLATION).");
    fieldErrors.setItems(fieldError);

    Schema<Object> problem =
        typed(
            "object",
            null,
            "An RFC 7807 problem. code is the stable, machine-readable reason; a client compares"
                + " it, never the localized title or detail.");
    problem.addProperty("type", typed("string", "uri", null));
    problem.addProperty("title", typed("string", null, null));
    problem.addProperty("status", typed("integer", "int32", null));
    problem.addProperty("detail", typed("string", null, null));
    problem.addProperty("instance", typed("string", "uri", null));
    problem.addProperty("code", code);
    problem.addProperty(
        "correlationId", typed("string", null, "Finds the request in the server log."));
    problem.addProperty("errors", errors);
    problem.addProperty("fieldErrors", fieldErrors);
    components.addSchemas(PROBLEM_SCHEMA, problem);
  }

  /**
   * Creates a schema of one JSON type, written as {@code type} by the OpenAPI 3.1 serializer.
   *
   * @param type the JSON type
   * @param format the format, or {@code null} for none
   * @param description the description, or {@code null} for none
   * @return the schema
   */
  @NotNull
  private static Schema<Object> typed(
      @NotNull String type, @Nullable String format, @Nullable String description) {
    Schema<Object> schema = new Schema<>();
    schema.setType(type);
    schema.setTypes(Set.of(type));
    schema.setFormat(format);
    schema.setDescription(description);
    return schema;
  }
}
