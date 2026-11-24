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

package de.greluc.krt.profit.basetool.ingest.contract;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.ingest.registry.ExchangeRegistry;
import de.greluc.krt.profit.basetool.ingest.registry.ExchangeRegistryReader;
import de.greluc.krt.profit.basetool.testsupport.exchange.ExchangeSeam;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Every area switch is read alone from the mirror member the backend writes it under, gates its own
 * routes and refuses them with the code both sides of the seam know (REQ-BXCH-006, REQ-OXCH-009).
 * The expected member, route and code of each switch are written out here, so a new switch without
 * its wiring fails to compile or fails a case.
 */
class AreaSwitchRoundTripTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @ParameterizedTest
  @EnumSource(AreaSwitch.class)
  void aSwitchIsReadAloneFromItsOwnMirrorMember(@NotNull AreaSwitch only) {
    ObjectNode document = (ObjectNode) MAPPER.readTree(ExchangeSeam.REGISTRY_SAMPLE);
    for (AreaSwitch any : AreaSwitch.values()) {
      document.put(mirrorMember(any), any == only);
    }

    ExchangeRegistry registry = ExchangeRegistryReader.parse(document);

    assertThat(ExchangeSeam.REGISTRY_DOCUMENT_FIELDS).contains(mirrorMember(only));
    assertThat(registry.areasOn()).containsExactly(only);
    assertThat(registry.isOn(only)).isTrue();
  }

  @ParameterizedTest
  @EnumSource(AreaSwitch.class)
  void aSwitchGatesItsOwnRouteAndRefusesWithTheSeamsCode(@NotNull AreaSwitch target) {
    assertThat(AreaSwitch.gating(method(target), path(target))).contains(target);
    assertThat(target.getCode()).isEqualTo(code(target));
    assertThat(ExchangeSeam.GATE_STATUSES)
        .containsEntry(target.getCode(), HttpStatus.SERVICE_UNAVAILABLE.value());
  }

  /**
   * Names the mirror document member the backend writes a switch under.
   *
   * @param target the switch
   * @return the member
   */
  private static @NotNull String mirrorMember(@NotNull AreaSwitch target) {
    return switch (target) {
      case BANK -> "bankEnabled";
      case JOB_ORDERS -> "jobOrdersEnabled";
      case JOB_ORDER_WRITES -> "jobOrderWritesEnabled";
    };
  }

  /**
   * Names the HTTP method of a request in a switch's area.
   *
   * @param target the switch
   * @return the method
   */
  private static @NotNull String method(@NotNull AreaSwitch target) {
    return switch (target) {
      case BANK, JOB_ORDERS -> "GET";
      case JOB_ORDER_WRITES -> "POST";
    };
  }

  /**
   * Names a path in a switch's area.
   *
   * @param target the switch
   * @return the path
   */
  private static @NotNull String path(@NotNull AreaSwitch target) {
    return switch (target) {
      case BANK -> "/exchange/v1/bank/balance";
      case JOB_ORDERS, JOB_ORDER_WRITES -> "/exchange/v1/job-orders";
    };
  }

  /**
   * Names the refusal code of a switch.
   *
   * @param target the switch
   * @return the code
   */
  private static @NotNull String code(@NotNull AreaSwitch target) {
    return switch (target) {
      case BANK -> "BANK_EXCHANGE_DISABLED";
      case JOB_ORDERS -> "JOB_ORDER_EXCHANGE_DISABLED";
      case JOB_ORDER_WRITES -> "JOB_ORDER_WRITES_DISABLED";
    };
  }
}
