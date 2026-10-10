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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins the tolerant-reading policy of REQ-API-022: the application's JSON mapper ignores a property
 * the target type does not declare.
 */
@SpringBootTest
class JacksonTolerantReadingTest {

  @Autowired private JsonMapper mapper;

  /** A request body naming a property the type lacks is read, and the property is dropped. */
  @Test
  void anUnknownPropertyIsIgnored() {
    Body body = mapper.readValue("{\"name\":\"x\",\"extra\":{\"nested\":1}}", Body.class);

    assertThat(body.name()).isEqualTo("x");
  }

  /** The feature that would turn the policy around stays off. */
  @Test
  void failOnUnknownPropertiesStaysOff() {
    assertThat(mapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isFalse();
  }

  /**
   * Request body fixture.
   *
   * @param name the only declared property
   */
  record Body(String name) {}
}
