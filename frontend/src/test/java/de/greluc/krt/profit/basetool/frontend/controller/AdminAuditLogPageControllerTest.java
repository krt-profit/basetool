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

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.model.dto.AuditEventDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.AuditRowView;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankAuditEventDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for {@link AdminAuditLogPageController}: the nine-way tab routing (bank vs the eight
 * generic areas), the adaptation of both DTO shapes into the uniform {@link AuditRowView}, the
 * per-tab export endpoint + event-type list, and the in-place fragment selector (REQ-AUDIT-002).
 */
@SuppressWarnings("unchecked")
class AdminAuditLogPageControllerTest {

  private BackendApiClient backendApiClient;
  private AdminAuditLogPageController controller;

  @BeforeEach
  void setUp() {
    backendApiClient = mock(BackendApiClient.class);
    controller = new AdminAuditLogPageController(backendApiClient);
  }

  @Test
  void bankTab_readsBankEndpointAndAdaptsAccountNoAsSubject() {
    // Given
    Model model = new ConcurrentModel();
    BankAuditEventDto bankRow =
        new BankAuditEventDto(
            UUID.randomUUID(),
            Instant.now(),
            "banker_jo",
            "DEPOSIT_BOOKED",
            UUID.randomUUID(),
            "KB-0001",
            null,
            null,
            "+100 aUEC");
    when(backendApiClient.get(contains("/api/v1/bank/admin/audit"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(bankRow), 0, 50, 1, 1, List.of()));

    // When
    String view = controller.auditLog("BANK", null, null, null, null, 0, null, model);

    // Then
    assertEquals("admin/audit-log", view);
    assertEquals("BANK", model.getAttribute("activeDomain"));
    assertEquals("/api/proxy/audit/BANK/export", model.getAttribute("exportEndpoint"));
    assertEquals("/api/proxy/audit/BANK", model.getAttribute("purgeEndpoint"));
    PageResponse<AuditRowView> events = (PageResponse<AuditRowView>) model.getAttribute("events");
    assertNotNull(events);
    AuditRowView row = events.content().get(0);
    assertEquals("KB-0001", row.subject());
    assertEquals("admin.bank.audit.event.DEPOSIT_BOOKED", row.eventLabelKey());
    List<String> eventTypes = (List<String>) model.getAttribute("eventTypes");
    assertTrue(eventTypes.contains("WIPE_RESET_EXECUTED"));
  }

  @Test
  void inventoryTab_readsGenericEndpointAndAdaptsSubjectLabel() {
    // Given
    Model model = new ConcurrentModel();
    AuditEventDto genericRow =
        new AuditEventDto(
            UUID.randomUUID(),
            Instant.now(),
            "INVENTORY",
            "INVENTORY_ITEM_CREATED",
            "logi_jo",
            UUID.randomUUID(),
            "Quantanium @ Port Olisar",
            null,
            "qty=5.0");
    when(backendApiClient.get(contains("/api/v1/audit/INVENTORY"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(genericRow), 0, 50, 1, 1, List.of()));

    // When
    String view = controller.auditLog("INVENTORY", null, null, null, null, 0, null, model);

    // Then
    assertEquals("admin/audit-log", view);
    assertEquals("INVENTORY", model.getAttribute("activeDomain"));
    assertEquals("/api/proxy/audit/INVENTORY/export", model.getAttribute("exportEndpoint"));
    assertEquals("/api/proxy/audit/INVENTORY", model.getAttribute("purgeEndpoint"));
    PageResponse<AuditRowView> events = (PageResponse<AuditRowView>) model.getAttribute("events");
    AuditRowView row = events.content().get(0);
    assertEquals("Quantanium @ Port Olisar", row.subject());
    assertEquals("admin.audit.event.INVENTORY_ITEM_CREATED", row.eventLabelKey());
  }

  @Test
  void promotionTab_readsGenericEndpointAndAdaptsSubjectLabel() {
    // Given
    Model model = new ConcurrentModel();
    AuditEventDto genericRow =
        new AuditEventDto(
            UUID.randomUUID(),
            Instant.now(),
            "PROMOTION",
            "PROMOTION_TOPIC_CREATED",
            "officer_jo",
            UUID.randomUUID(),
            "Grundlagen",
            null,
            null);
    when(backendApiClient.get(contains("/api/v1/audit/PROMOTION"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(genericRow), 0, 50, 1, 1, List.of()));

    // When
    String view = controller.auditLog("PROMOTION", null, null, null, null, 0, null, model);

    // Then
    assertEquals("admin/audit-log", view);
    assertEquals("PROMOTION", model.getAttribute("activeDomain"));
    assertEquals("/api/proxy/audit/PROMOTION/export", model.getAttribute("exportEndpoint"));
    assertEquals("/api/proxy/audit/PROMOTION", model.getAttribute("purgeEndpoint"));
    PageResponse<AuditRowView> events = (PageResponse<AuditRowView>) model.getAttribute("events");
    AuditRowView row = events.content().get(0);
    assertEquals("Grundlagen", row.subject());
    assertEquals("admin.audit.event.PROMOTION_TOPIC_CREATED", row.eventLabelKey());
    List<String> eventTypes = (List<String>) model.getAttribute("eventTypes");
    assertTrue(eventTypes.contains("PROMOTION_EVALUATION_CREATED"));
  }

  @Test
  void unknownDomain_fallsBackToBankTab() {
    // Given
    Model model = new ConcurrentModel();
    when(backendApiClient.get(any(String.class), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(), 0, 50, 0, 0, List.of()));

    // When
    controller.auditLog("NONSENSE", null, null, null, null, 0, null, model);

    // Then
    assertEquals("BANK", model.getAttribute("activeDomain"));
  }

  @Test
  void fragmentResults_returnsResultsFragmentSelector() {
    // Given
    Model model = new ConcurrentModel();
    when(backendApiClient.get(any(String.class), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(), 0, 50, 0, 0, List.of()));

    // When
    String view = controller.auditLog("REFINERY", null, null, null, null, 0, "results", model);

    // Then
    assertEquals("admin/audit-log :: auditResults", view);
  }

  // Guard against the recurrence the review caught (a new event type produced at runtime but not
  // wired into the viewer): every bank audit event type the backend can emit — the openapi enum of
  // BankAuditEventDto.eventType, the cross-module contract — must appear in the BANK filter list
  // AND
  // carry an i18n label, or it would render as a raw key (CLAUDE.md audited-area rule).
  @Test
  void everyProducedBankAuditEventType_isFilterableAndLabelled() throws Exception {
    // Given: the produced set (from openapi) and the viewer's BANK filter list + the label bundle
    Set<String> produced = bankAuditEventTypesFromOpenApi();
    assertTrue(produced.contains("HOLDER_TRANSFER"), "sanity: openapi should list HOLDER_TRANSFER");

    Model model = new ConcurrentModel();
    when(backendApiClient.get(any(String.class), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(), 0, 50, 0, 0, List.of()));
    controller.auditLog("BANK", null, null, null, null, 0, null, model);
    List<String> filterTypes = (List<String>) model.getAttribute("eventTypes");
    assertNotNull(filterTypes);
    Properties labels = loadDefaultBundle();

    // Then: each produced type is both filterable and labelled
    for (String type : produced) {
      assertTrue(
          filterTypes.contains(type),
          "BANK audit filter list (AdminAuditLogPageController) is missing produced event type "
              + type);
      assertTrue(
          labels.containsKey("admin.bank.audit.event." + type),
          "Missing i18n label admin.bank.audit.event." + type);
    }
  }

  /**
   * The bank audit event types the backend can emit, read from the {@code BankAuditEventDto
   * .eventType} enum in the committed openapi document — the cross-module contract between the
   * (backend) enum and the (frontend) viewer.
   *
   * @return the produced bank audit event-type names
   * @throws Exception when the spec cannot be located or parsed
   */
  private static Set<String> bankAuditEventTypesFromOpenApi() throws Exception {
    Path relative = Paths.get("backend", "src", "main", "resources", "api", "openapi.json");
    Path spec = null;
    for (Path dir = Paths.get("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
      Path candidate = dir.resolve(relative);
      if (Files.isRegularFile(candidate)) {
        spec = candidate;
        break;
      }
    }
    assertNotNull(spec, "Could not locate backend/src/main/resources/api/openapi.json");
    JsonNode root = JsonMapper.builder().build().readTree(Files.readString(spec));
    JsonNode enumNode =
        root.path("components")
            .path("schemas")
            .path("BankAuditEventDto")
            .path("properties")
            .path("eventType")
            .path("enum");
    assertTrue(
        enumNode.isArray() && !enumNode.isEmpty(),
        "BankAuditEventDto.eventType enum not found in openapi.json");
    Set<String> types = new HashSet<>();
    for (JsonNode value : enumNode) {
      types.add(value.asString());
    }
    return types;
  }

  /**
   * Loads the default {@code messages.properties} bundle (where {@code Properties.load} decodes the
   * {@code \\uXXXX} escapes); the de/en bundles mirror its keys (pinned by {@code
   * MessageBundleConsistencyTest}).
   *
   * @return the default message bundle
   * @throws Exception when the bundle cannot be read
   */
  private static Properties loadDefaultBundle() throws Exception {
    Properties bundle = new Properties();
    try (InputStream in =
        AdminAuditLogPageControllerTest.class.getResourceAsStream("/messages.properties")) {
      assertNotNull(in, "messages.properties not on the test classpath");
      bundle.load(in);
    }
    return bundle;
  }

  @Test
  void backendFailure_setsErrorAttribute() {
    // Given
    Model model = new ConcurrentModel();
    when(backendApiClient.get(any(String.class), anyTypeRef()))
        .thenThrow(new RuntimeException("down"));

    // When
    controller.auditLog("JOB_ORDER", null, null, null, null, 0, null, model);

    // Then
    assertEquals("admin.audit.error.load", model.getAttribute("error"));
  }
}
