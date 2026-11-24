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

package de.greluc.krt.profit.basetool.backend.exchange.web;

import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeSwitch;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Documents the area-switch endpoint of {@link AdminExchangeRegistryController} as one path per
 * {@link ExchangeSwitch.Area} (REQ-API-007, REQ-BXCH-006, REQ-OXCH-009): {@code GET} and {@code
 * PUT} of {@code /settings/<segment>} with the operation ids {@code get<Segment>Switch} and {@code
 * update<Segment>Switch}, the switch's own name and what the gateway answers while it is off. The
 * endpoint serves no other segment.
 */
@Configuration
public class AreaSwitchOpenApi {

  /** The path the controller maps the area switches to. */
  static final String TEMPLATE =
      AdminExchangeRegistryController.BASE_PATH + AdminExchangeRegistryController.AREA_SWITCH_PATH;

  /** The path of an area switch without its segment. */
  private static final String AREA_PREFIX =
      AdminExchangeRegistryController.BASE_PATH + "/settings/";

  /** The path variable naming the area. */
  private static final String AREA_VARIABLE = "area";

  /** The success response of both operations. */
  private static final String OK = "200";

  /**
   * Returns the customizer that replaces the templated area-switch path by one path per area.
   *
   * @return the OpenAPI customizer
   */
  @Bean
  public OpenApiCustomizer areaSwitchOpenApiCustomizer() {
    return openApi -> expand(openApi.getPaths());
  }

  /**
   * Replaces the templated area-switch path by one path per area; leaves a document without it
   * alone.
   *
   * @param paths the document's paths, changed in place
   */
  static void expand(@Nullable Paths paths) {
    if (paths == null) {
      return;
    }
    PathItem template = paths.remove(TEMPLATE);
    if (template == null) {
      return;
    }
    for (ExchangeSwitch target : ExchangeSwitch.values()) {
      target
          .area()
          .ifPresent(
              area -> paths.addPathItem(AREA_PREFIX + area.segment(), pathItem(template, area)));
    }
  }

  /**
   * Builds the documented path of one area switch.
   *
   * @param template the templated path item
   * @param area the area switch
   * @return its path item
   */
  private static @NotNull PathItem pathItem(
      @NotNull PathItem template, @NotNull ExchangeSwitch.Area area) {
    String name = pascal(area.segment());
    return new PathItem()
        .get(
            operation(
                template.getGet(),
                "get" + name + "Switch",
                "Get the " + area.label() + " switch of the exchange",
                null,
                "The " + area.label() + " switch"))
        .put(
            operation(
                template.getPut(),
                "update" + name + "Switch",
                "Set the " + area.label() + " switch of the exchange",
                "While it is off, "
                    + area.offAnswer()
                    + "; switching off reaches the gateway's mirror before the commit or not at"
                    + " all.",
                "The saved " + area.label() + " switch"));
  }

  /**
   * Copies a templated operation for one area: its own id, summary, description and success
   * description, without the area path variable.
   *
   * @param template the templated operation
   * @param operationId the area's operation id
   * @param summary the area's summary
   * @param description the area's description, or {@code null} for none
   * @param okDescription the description of the area's success response
   * @return the area's operation
   */
  private static @NotNull Operation operation(
      @NotNull Operation template,
      @NotNull String operationId,
      @NotNull String summary,
      @Nullable String description,
      @NotNull String okDescription) {
    List<Parameter> parameters =
        template.getParameters() == null
            ? List.of()
            : template.getParameters().stream()
                .filter(parameter -> !AREA_VARIABLE.equals(parameter.getName()))
                .toList();
    Operation operation =
        new Operation()
            .tags(template.getTags())
            .summary(summary)
            .description(description)
            .operationId(operationId)
            .parameters(parameters.isEmpty() ? null : parameters)
            .requestBody(template.getRequestBody())
            .responses(responses(template.getResponses(), okDescription))
            .security(template.getSecurity())
            .deprecated(template.getDeprecated());
    operation.setExtensions(
        template.getExtensions() == null ? null : new LinkedHashMap<>(template.getExtensions()));
    return operation;
  }

  /**
   * Copies the responses of a templated operation with the area's success description.
   *
   * @param template the templated responses
   * @param okDescription the description of the success response
   * @return the area's responses
   */
  private static @NotNull ApiResponses responses(
      @NotNull ApiResponses template, @NotNull String okDescription) {
    ApiResponses responses = new ApiResponses();
    template.forEach((code, response) ->
        responses.addApiResponse(
            code, OK.equals(code) ? withDescription(response, okDescription) : response));
    return responses;
  }

  /**
   * Copies a response with another description.
   *
   * @param response the templated response
   * @param description the area's description
   * @return the copy
   */
  private static @NotNull ApiResponse withDescription(
      @NotNull ApiResponse response, @NotNull String description) {
    return new ApiResponse()
        .description(description)
        .content(response.getContent())
        .headers(response.getHeaders() == null ? null : new LinkedHashMap<>(response.getHeaders()))
        .links(response.getLinks())
        .extensions(response.getExtensions());
  }

  /**
   * Turns a path segment into the Pascal case of an operation id: {@code job-orders} becomes {@code
   * JobOrders}.
   *
   * @param segment the path segment
   * @return the Pascal-case name
   */
  private static @NotNull String pascal(@NotNull String segment) {
    return Arrays.stream(segment.split("-"))
        .map(word -> word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1))
        .collect(Collectors.joining());
  }
}
