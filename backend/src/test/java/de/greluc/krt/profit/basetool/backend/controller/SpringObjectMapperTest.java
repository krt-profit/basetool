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

package de.greluc.krt.profit.basetool.backend.controller;

import de.greluc.krt.profit.basetool.backend.model.dto.RefineryOrderDto;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

public class SpringObjectMapperTest {

  private JsonMapper mapper =
      JsonMapper.builder()
          .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, false)
          .build();

  @Test
  public void testDeserialize() throws Exception {
    Map<String, Object> orderDto = new HashMap<>();

    OffsetDateTime startedAtTime = OffsetDateTime.now();
    orderDto.put("startedAt", startedAtTime.toInstant().toString());

    orderDto.put("location", Map.of("id", UUID.randomUUID()));
    orderDto.put("mission", Map.of("id", UUID.randomUUID()));
    orderDto.put("durationMinutes", 100);
    orderDto.put("refiningMethod", Map.of("id", UUID.randomUUID()));
    orderDto.put("expenses", 500);
    orderDto.put("status", "OPEN");

    List<Map<String, Object>> goodsDto = new ArrayList<>();
    Map<String, Object> good = new HashMap<>();
    good.put("inputMaterial", Map.of("id", UUID.randomUUID()));
    good.put("inputQuantity", 100.0);
    good.put("outputMaterial", Map.of("id", UUID.randomUUID()));
    good.put("outputQuantity", 50.0);
    good.put("quality", 100);
    goodsDto.add(good);

    orderDto.put("goods", goodsDto);

    String json = mapper.writeValueAsString(orderDto);

    RefineryOrderDto dto = mapper.readValue(json, RefineryOrderDto.class);
  }
}
