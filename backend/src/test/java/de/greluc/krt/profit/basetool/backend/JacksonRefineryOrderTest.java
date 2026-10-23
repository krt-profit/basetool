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

package de.greluc.krt.profit.basetool.backend;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.refinery.internal.RefineryOrder;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

public class JacksonRefineryOrderTest {
  @Test
  public void testDeserialization() throws Exception {
    JsonMapper mapper = JsonMapper.builder().build();

    Map<String, Object> orderDto = new HashMap<>();
    orderDto.put("startedAt", "2026-03-27T18:35:59Z");
    orderDto.put("durationMinutes", 10);
    orderDto.put("expenses", 100);
    orderDto.put("location", Map.of("id", UUID.randomUUID().toString()));
    orderDto.put("mission", Map.of("id", UUID.randomUUID().toString()));
    orderDto.put("refiningMethod", Map.of("id", UUID.randomUUID().toString()));

    List<Map<String, Object>> goodsDto = new ArrayList<>();
    Map<String, Object> good = new HashMap<>();
    good.put("inputMaterial", Map.of("id", UUID.randomUUID().toString()));
    good.put("inputQuantity", 100);
    good.put("outputQuantity", 200);
    good.put("quality", 500);
    goodsDto.add(good);

    orderDto.put("goods", goodsDto);

    String json = mapper.writeValueAsString(orderDto);

    RefineryOrder order = mapper.readValue(json, RefineryOrder.class);

    assertThat(order.getDurationMinutes()).isEqualTo(10L);
    assertThat(order.getExpenses()).isEqualTo(100.0);
  }
}
