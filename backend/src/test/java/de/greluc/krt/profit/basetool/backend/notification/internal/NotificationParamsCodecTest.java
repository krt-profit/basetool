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

package de.greluc.krt.profit.basetool.backend.notification.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class NotificationParamsCodecTest {

  private final NotificationParamsCodec codec =
      new NotificationParamsCodec(JsonMapper.builder().build());

  @Test
  void serializeThenDeserializeRoundTrips() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("displayId", "42");
    params.put("orgUnit", "IRI");

    String json = codec.serialize(params);
    Map<String, String> back = codec.deserialize(json);

    assertEquals(params, back);
  }

  @Test
  void serializeNullOrEmptyReturnsNull() {
    assertNull(codec.serialize(null));
    assertNull(codec.serialize(Map.of()));
  }

  @Test
  void deserializeNullOrBlankReturnsEmptyMap() {
    assertTrue(codec.deserialize(null).isEmpty());
    assertTrue(codec.deserialize("   ").isEmpty());
  }

  @Test
  void deserializeMalformedReturnsEmptyMap() {
    assertTrue(codec.deserialize("{not valid json").isEmpty());
  }
}
