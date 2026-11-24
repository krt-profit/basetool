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

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeSwitch;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.parameters.PathParameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Every area switch gets its own documented path in the admin API document, under the operation ids
 * the frontend and earlier documents know (REQ-API-007, REQ-BXCH-006, REQ-OXCH-009).
 */
class AreaSwitchOpenApiTest {

  @ParameterizedTest
  @EnumSource(value = ExchangeSwitch.class, names = "GLOBAL", mode = EnumSource.Mode.EXCLUDE)
  void everyAreaSwitchIsDocumentedUnderItsOwnPath(@NotNull ExchangeSwitch target) {
    Paths paths = new Paths();
    paths.addPathItem(AreaSwitchOpenApi.TEMPLATE, template());

    AreaSwitchOpenApi.expand(paths);

    assertThat(paths).doesNotContainKey(AreaSwitchOpenApi.TEMPLATE);
    PathItem item = paths.get("/api/v1/connected-apps/admin/settings/" + segment(target));
    assertThat(item).as("the path of %s", target).isNotNull();
    assertThat(item.getGet().getOperationId()).isEqualTo("get" + name(target) + "Switch");
    assertThat(item.getPut().getOperationId()).isEqualTo("update" + name(target) + "Switch");
    assertThat(item.getGet().getParameters()).isNull();
    assertThat(item.getPut().getParameters()).isNull();
    assertThat(item.getPut().getDescription()).startsWith("While it is off, every ");
    assertThat(item.getPut().getResponses().get("200").getDescription()).startsWith("The saved ");
  }

  /**
   * Builds the templated path item the controller produces.
   *
   * @return the path item
   */
  private static @NotNull PathItem template() {
    return new PathItem().get(operation("getAreaSwitch")).put(operation("updateAreaSwitch"));
  }

  /**
   * Builds a templated operation with the area path variable and a success response.
   *
   * @param operationId the template's operation id
   * @return the operation
   */
  private static @NotNull Operation operation(@NotNull String operationId) {
    return new Operation()
        .operationId(operationId)
        .parameters(List.of(new PathParameter().name("area")))
        .responses(new ApiResponses().addApiResponse("200", new ApiResponse().description("OK")));
  }

  /**
   * Names the path segment of an area switch.
   *
   * @param target the switch
   * @return its segment
   */
  private static @NotNull String segment(@NotNull ExchangeSwitch target) {
    return switch (target) {
      case GLOBAL -> throw new IllegalArgumentException("The global switch has no area path");
      case BANK -> "bank";
      case JOB_ORDERS -> "job-orders";
      case JOB_ORDER_WRITES -> "job-order-writes";
    };
  }

  /**
   * Names an area switch in its operation ids.
   *
   * @param target the switch
   * @return its name
   */
  private static @NotNull String name(@NotNull ExchangeSwitch target) {
    return switch (target) {
      case GLOBAL -> throw new IllegalArgumentException("The global switch has no area path");
      case BANK -> "Bank";
      case JOB_ORDERS -> "JobOrders";
      case JOB_ORDER_WRITES -> "JobOrderWrites";
    };
  }
}
