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

package de.greluc.krt.profit.basetool.backend.exchange.internal.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** A ship upsert tells an absent org unit from an explicit {@code null} (REQ-XCH-017). */
class ExchangeShipChangeSetJsonTest {

  private final JsonMapper mapper = JsonMapper.builder().build();

  @Test
  void anAbsentOrgUnitReadsAsNoChoice() {
    assertThat(read("").orgUnit()).isNull();
  }

  @Test
  void anExplicitNullReadsAsTheChoiceOfNoUnit() {
    ExchangeOrgUnitChoice choice = read(",\"orgUnit\":null").orgUnit();

    assertThat(choice).isNotNull();
    assertThat(choice.orgUnitId()).isNull();
  }

  @Test
  void aUnitIdReadsAsTheChoiceOfThatUnit() {
    ExchangeOrgUnitChoice choice = read(",\"orgUnit\":\"u-1\",\"orgUnitVersion\":3").orgUnit();

    assertThat(choice).isNotNull();
    assertThat(choice.orgUnitId()).isEqualTo("u-1");
  }

  private ExchangeShipChangeSet.@NotNull Op read(@NotNull String orgUnitFields) {
    String json = "{\"ops\":[{\"op\":\"upsert\",\"externalId\":\"vk-1\"" + orgUnitFields + "}]}";
    return mapper.readValue(json, ExchangeShipChangeSet.class).ops().getFirst();
  }
}
