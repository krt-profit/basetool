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

/**
 * Unit tests for the {@link MonitoringScrapeProperties#isConfigured()} gate: the scrape chain only
 * enables basic auth when a complete, non-blank credential pair is present — every partial or blank
 * state must keep the endpoint fail-closed (REQ-OBS-005).
 */
class MonitoringScrapePropertiesTest {

  @Test
  void shouldBeConfiguredWhenBothValuesArePresent() {
    // Given
    MonitoringScrapeProperties properties = new MonitoringScrapeProperties();
    properties.setUsername("metrics-scraper");
    properties.setPassword("test-scrape-password");

    // When / Then
    assertThat(properties.isConfigured()).isTrue();
  }

  @Test
  void shouldNotBeConfiguredByDefault() {
    // Given
    MonitoringScrapeProperties properties = new MonitoringScrapeProperties();

    // When / Then: unset env vars bind to empty strings — the fail-closed default.
    assertThat(properties.isConfigured()).isFalse();
  }

  @Test
  void shouldNotBeConfiguredWithBlankUsername() {
    // Given
    MonitoringScrapeProperties properties = new MonitoringScrapeProperties();
    properties.setUsername("   ");
    properties.setPassword("test-scrape-password");

    // When / Then
    assertThat(properties.isConfigured()).isFalse();
  }

  @Test
  void shouldNotBeConfiguredWithBlankPassword() {
    // Given
    MonitoringScrapeProperties properties = new MonitoringScrapeProperties();
    properties.setUsername("metrics-scraper");
    properties.setPassword("");

    // When / Then
    assertThat(properties.isConfigured()).isFalse();
  }

  @Test
  void shouldNotBeConfiguredWithNullValues() {
    // Given
    MonitoringScrapeProperties properties = new MonitoringScrapeProperties();
    properties.setUsername(null);
    properties.setPassword(null);

    // When / Then
    assertThat(properties.isConfigured()).isFalse();
  }
}
