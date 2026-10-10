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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Pins the exchange resource vocabulary over all constants (plan §8.1). */
class ExchangeResourceTest {

  @Test
  void everyResourceHasItsMassChangeNameCapabilityAndMetricTag() {
    Map<ExchangeResource, String> names =
        Map.of(
            ExchangeResource.BLUEPRINT, "blueprints",
            ExchangeResource.STOCK, "stock",
            ExchangeResource.SHIP, "ships");
    Map<ExchangeResource, ExchangeCapability> capabilities =
        Map.of(
            ExchangeResource.BLUEPRINT, ExchangeCapability.BLUEPRINTS_WRITE,
            ExchangeResource.STOCK, ExchangeCapability.STOCK_WRITE,
            ExchangeResource.SHIP, ExchangeCapability.HANGAR_WRITE);
    Map<ExchangeResource, String> tags =
        Map.of(
            ExchangeResource.BLUEPRINT, "blueprint",
            ExchangeResource.STOCK, "stock",
            ExchangeResource.SHIP, "ship");

    assertThat(names).containsOnlyKeys(Arrays.asList(ExchangeResource.values()));
    for (ExchangeResource resource : ExchangeResource.values()) {
      assertThat(resource.massChangeName()).isEqualTo(names.get(resource));
      assertThat(resource.writeCapability()).isEqualTo(capabilities.get(resource));
      assertThat(resource.metricTag()).isEqualTo(tags.get(resource));
      assertThat(ExchangeResource.fromMassChangeName(resource.massChangeName())).contains(resource);
    }
  }

  @Test
  void anyOtherNameMapsToNoResource() {
    for (String name : new String[] {null, "", "hangar", "ship", "SHIPS", "blueprint", " stock"}) {
      assertThat(ExchangeResource.fromMassChangeName(name)).as(String.valueOf(name)).isEmpty();
    }
  }
}
