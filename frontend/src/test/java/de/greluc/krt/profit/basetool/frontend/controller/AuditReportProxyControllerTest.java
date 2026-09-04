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

package de.greluc.krt.profit.basetool.frontend.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for {@link AuditReportProxyController}: the raw {@code domain} path segment is
 * validated against the known-tab allowlist before any backend URI is built, so an unknown or
 * crafted value (e.g. containing {@code ..}) is rejected with 400 and never reaches the {@link
 * WebClient} — defense-in-depth for the export and purge proxies (REQ-AUDIT-002/-004).
 */
class AuditReportProxyControllerTest {

  private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant TO = Instant.parse("2026-02-01T00:00:00Z");

  private final WebClient webClient = mock(WebClient.class);
  private final AuditReportProxyController controller = new AuditReportProxyController(webClient);

  @Test
  void pdfExport_rejectsUnknownDomain() {
    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () -> controller.downloadAuditLog("EVIL", FROM, TO, null));
    assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
  }

  @Test
  void jsonExport_rejectsCraftedDomain() {
    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () -> controller.downloadAuditLogJson("../../etc", FROM, TO));
    assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
  }

  @Test
  void purge_rejectsUnknownDomain() {
    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class, () -> controller.purgeAuditLog("nonsense", FROM));
    assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
  }

  @Test
  void marketDomain_passesTheAllowlistGate() {
    // Regression: the proxy kept its own copy of the tab list and never gained MARKET, so the
    // Materialbörse tab rendered but its export / JSON / purge buttons answered 400. Both classes
    // now read AuditDomains.ALL.
    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () -> controller.downloadAuditLog("MARKET", FROM, TO, null));
    assertNotEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
  }

  @Test
  void marketDomain_passesTheAllowlistGateOnThePurgePath() {
    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> controller.purgeAuditLog("MARKET", FROM));
    assertNotEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
  }

  @Test
  void knownDomain_passesTheAllowlistGate() {
    // A known tab passes validation and then fails downstream in the bare WebClient mock (wrapped
    // 500), proving the 400 allowlist gate did not reject a legitimate domain.
    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () -> controller.downloadAuditLog("BANK", FROM, TO, null));
    assertNotEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
  }
}
