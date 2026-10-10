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

import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Pins the {@code uri} tag cap of {@code http.server.requests} above the route count (REQ-OBS-006).
 */
@SpringBootTest
class UriTagCapacityTest {

  /** Tags beside the route templates: {@code UNKNOWN}, {@code NOT_FOUND}, {@code REDIRECTION}, … */
  private static final int HEADROOM = 50;

  /** Selection floor: the distinct route templates when the guard was introduced. */
  private static final int MIN_ROUTE_TEMPLATES = 448;

  @Autowired private ApplicationContext applicationContext;
  @Autowired private MetricsProperties metricsProperties;

  @Test
  @DisplayName("the uri tag cap exceeds the number of route templates")
  void capCoversEveryRouteTemplate() {
    RequestMappingHandlerMapping handlerMapping =
        applicationContext.getBean(
            "requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
    Set<String> templates = new TreeSet<>();
    for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
      templates.addAll(info.getPatternValues());
    }
    int cap = metricsProperties.getWeb().getServer().getMaxUriTags();

    assertThat(templates)
        .as("selection floor: the dispatcher must report at least today's route templates")
        .hasSizeGreaterThanOrEqualTo(MIN_ROUTE_TEMPLATES);
    assertThat(templates.size() + HEADROOM)
        .as(
            "management.metrics.web.server.max-uri-tags (%d) must exceed the %d route templates"
                + " plus %d fixed tags, or every route first hit after the cap goes unmetered",
            cap, templates.size(), HEADROOM)
        .isLessThanOrEqualTo(cap);
  }
}
