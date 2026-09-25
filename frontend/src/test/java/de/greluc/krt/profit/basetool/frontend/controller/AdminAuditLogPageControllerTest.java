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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
import org.mockito.ArgumentCaptor;
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

  /**
   * The nine non-bank area tabs, mirroring the controller's private domain list. Every generic
   * audit event type the backend can emit must be offered by one of these tabs.
   */
  private static final List<String> GENERIC_DOMAINS =
      List.of(
          "INVENTORY",
          "JOB_ORDER",
          "REFINERY",
          "PERSONAL_INVENTORY",
          "MISSION",
          "OPERATION",
          "ROLE",
          "PROMOTION",
          "MARKET");

  private BackendApiClient backendApiClient;
  private AdminAuditLogPageController controller;

  @BeforeEach
  void setUp() {
    backendApiClient = mock(BackendApiClient.class);
    controller = new AdminAuditLogPageController(backendApiClient);
  }

  @Test
  void bankTab_readsBankEndpointAndAdaptsAccountNoAsSubject() {
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
            "+100 aUEC",
            "basetool-frontend");
    when(backendApiClient.get(contains("/api/v1/bank/admin/audit"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(bankRow), 0, 50, 1, 1, List.of()));

    String view = controller.auditLog("BANK", null, null, null, null, null, 0, null, model);

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
            "qty=5.0",
            "basetool-android");
    when(backendApiClient.get(contains("/api/v1/audit/INVENTORY"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(genericRow), 0, 50, 1, 1, List.of()));

    String view = controller.auditLog("INVENTORY", null, null, null, null, null, 0, null, model);

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
            null,
            "basetool-frontend");
    when(backendApiClient.get(contains("/api/v1/audit/PROMOTION"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(genericRow), 0, 50, 1, 1, List.of()));

    String view = controller.auditLog("PROMOTION", null, null, null, null, null, 0, null, model);

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
  void roleTab_offersRolePermissionsChangedFilterWithLabel() throws Exception {
    Model model = new ConcurrentModel();
    when(backendApiClient.get(any(String.class), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(), 0, 50, 0, 0, List.of()));

    controller.auditLog("ROLE", null, null, null, null, null, 0, null, model);

    List<String> eventTypes = (List<String>) model.getAttribute("eventTypes");
    assertNotNull(eventTypes);
    assertTrue(
        eventTypes.contains("ROLE_PERMISSIONS_CHANGED"),
        "ROLE audit filter list is missing ROLE_PERMISSIONS_CHANGED");
    assertEquals("admin.audit.event.", model.getAttribute("eventKeyPrefix"));
    Properties labels = loadDefaultBundle();
    assertTrue(
        labels.containsKey("admin.audit.event.ROLE_PERMISSIONS_CHANGED"),
        "Missing i18n label admin.audit.event.ROLE_PERMISSIONS_CHANGED");
  }

  @Test
  void unknownDomain_fallsBackToBankTab() {
    Model model = new ConcurrentModel();
    when(backendApiClient.get(any(String.class), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(), 0, 50, 0, 0, List.of()));

    controller.auditLog("NONSENSE", null, null, null, null, null, 0, null, model);

    assertEquals("BANK", model.getAttribute("activeDomain"));
  }

  @Test
  void fragmentResults_returnsResultsFragmentSelector() {
    Model model = new ConcurrentModel();
    when(backendApiClient.get(any(String.class), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(), 0, 50, 0, 0, List.of()));

    String view =
        controller.auditLog("REFINERY", null, null, null, null, null, 0, "results", model);

    assertEquals("admin/audit-log :: auditResults", view);
  }

  @Test
  void everyProducedBankAuditEventType_isFilterableAndLabelled() throws Exception {
    Set<String> produced = bankAuditEventTypesFromOpenApi();
    assertTrue(produced.contains("HOLDER_TRANSFER"), "sanity: openapi should list HOLDER_TRANSFER");

    Model model = new ConcurrentModel();
    when(backendApiClient.get(any(String.class), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(), 0, 50, 0, 0, List.of()));
    controller.auditLog("BANK", null, null, null, null, null, 0, null, model);
    List<String> filterTypes = (List<String>) model.getAttribute("eventTypes");
    assertNotNull(filterTypes);
    Properties labels = loadDefaultBundle();

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

  @Test
  void everyProducedGenericAuditEventType_isFilterableAndLabelled() throws Exception {
    Set<String> produced = genericAuditEventTypesFromOpenApi();
    assertTrue(
        produced.contains("MEMBERSHIP_GRANTED"),
        "sanity: openapi should list the ROLE-domain MEMBERSHIP_GRANTED");

    when(backendApiClient.get(any(String.class), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(), 0, 50, 0, 0, List.of()));
    Set<String> filterable = new HashSet<>();
    for (String domain : GENERIC_DOMAINS) {
      Model model = new ConcurrentModel();
      controller.auditLog(domain, null, null, null, null, null, 0, null, model);
      List<String> types = (List<String>) model.getAttribute("eventTypes");
      assertNotNull(types, "no event-type list for domain " + domain);
      filterable.addAll(types);
    }
    Properties labels = loadDefaultBundle();

    for (String type : produced) {
      assertTrue(
          filterable.contains(type),
          "No area tab (AdminAuditLogPageController) offers produced event type " + type);
      assertTrue(
          labels.containsKey("admin.audit.event." + type),
          "Missing i18n label admin.audit.event." + type);
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
    return auditEventTypesFromOpenApi("BankAuditEventDto");
  }

  /**
   * The generic-area audit event types the backend can emit, read from the {@code
   * AuditEventDto.eventType} enum in the committed openapi document. Covers all nine non-bank
   * domains at once — the document does not say which domain a type belongs to, so the assertion
   * above checks membership in the union of the nine per-tab lists.
   *
   * @return the produced generic audit event-type names
   * @throws Exception when the spec cannot be located or parsed
   */
  private static Set<String> genericAuditEventTypesFromOpenApi() throws Exception {
    return auditEventTypesFromOpenApi("AuditEventDto");
  }

  /**
   * Reads the {@code eventType} enum of one audit DTO schema out of the committed openapi document,
   * walking up from the working directory until {@code backend/src/main/resources/api/openapi.json}
   * is found (the test runs from the module directory, the spec lives in the sibling module).
   *
   * @param schema the openapi schema name, {@code BankAuditEventDto} or {@code AuditEventDto}
   * @return the enum constant names declared for that schema's {@code eventType}
   * @throws Exception when the spec cannot be located or parsed
   */
  private static Set<String> auditEventTypesFromOpenApi(String schema) throws Exception {
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
            .path(schema)
            .path("properties")
            .path("eventType")
            .path("enum");
    assertTrue(
        enumNode.isArray() && !enumNode.isEmpty(),
        schema + ".eventType enum not found in openapi.json");
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
  void genericTab_forwardsTheClientFilterAndRendersTheRowsClient() {
    Model model = new ConcurrentModel();
    AuditEventDto row =
        new AuditEventDto(
            UUID.randomUUID(),
            Instant.now(),
            "ROLE",
            "ROLE_GRANTED",
            "admin_jo",
            UUID.randomUUID(),
            "IRIDIUM",
            UUID.randomUUID(),
            "rank=OL",
            "basetool-android");
    when(backendApiClient.get(contains("/api/v1/audit/ROLE"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(row), 0, 50, 1, 1, List.of()));

    controller.auditLog("ROLE", null, null, null, null, "basetool-android", 0, null, model);

    ArgumentCaptor<String> uri = ArgumentCaptor.forClass(String.class);
    verify(backendApiClient).get(uri.capture(), anyTypeRef());
    assertTrue(
        uri.getValue().contains("clientId=basetool-android"),
        "the client filter must reach the backend, not just the page: " + uri.getValue());

    assertEquals("basetool-android", model.getAttribute("filterClientId"));
    PageResponse<AuditRowView> events = (PageResponse<AuditRowView>) model.getAttribute("events");
    assertNotNull(events);
    assertEquals("basetool-android", events.content().getFirst().clientId());
  }

  @Test
  void unknownClientFilter_isDroppedRatherThanRelayed() {
    Model model = new ConcurrentModel();
    when(backendApiClient.get(any(String.class), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(), 0, 50, 0, 0, List.of()));

    controller.auditLog("MISSION", null, null, null, null, "other&size=9999", 0, null, model);

    ArgumentCaptor<String> uri = ArgumentCaptor.forClass(String.class);
    verify(backendApiClient).get(uri.capture(), anyTypeRef());
    assertFalse(
        uri.getValue().contains("clientId"),
        "an unknown client filter must not reach the backend: " + uri.getValue());
    assertNull(model.getAttribute("filterClientId"));
  }

  @Test
  void eventTypeFilter_isNarrowedToTheActiveTabsOwnTypes() {
    Model model = new ConcurrentModel();
    when(backendApiClient.get(any(String.class), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(), 0, 50, 0, 0, List.of()));

    controller.auditLog("MISSION", null, null, null, "ACCOUNT_CREATED", null, 0, null, model);

    ArgumentCaptor<String> uri = ArgumentCaptor.forClass(String.class);
    verify(backendApiClient).get(uri.capture(), anyTypeRef());
    assertFalse(
        uri.getValue().contains("eventType"),
        "a bank event type must not be relayed on the mission tab: " + uri.getValue());
    assertNull(model.getAttribute("filterEventType"));
  }

  @Test
  void periodAndActorFilters_areRelayedInTheirCanonicalForm() {
    Model model = new ConcurrentModel();
    Instant from = Instant.parse("2026-01-01T00:00:00Z");
    Instant to = Instant.parse("2026-02-01T00:00:00Z");
    UUID actor = UUID.fromString("11111111-2222-3333-4444-555555555555");
    when(backendApiClient.get(any(String.class), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(), 0, 50, 0, 0, List.of()));

    controller.auditLog("MISSION", from, to, actor, null, null, 0, null, model);

    ArgumentCaptor<String> uri = ArgumentCaptor.forClass(String.class);
    verify(backendApiClient).get(uri.capture(), anyTypeRef());
    assertTrue(uri.getValue().contains("from=2026-01-01T00:00:00Z"), uri.getValue());
    assertTrue(uri.getValue().contains("to=2026-02-01T00:00:00Z"), uri.getValue());
    assertTrue(uri.getValue().contains("actorUserId=" + actor), uri.getValue());
  }

  @Test
  void genericTab_keepsTheClientFilterAcrossPaging() {
    Model model = new ConcurrentModel();
    when(backendApiClient.get(any(String.class), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(), 0, 50, 0, 0, List.of()));

    controller.auditLog("MISSION", null, null, null, null, "other", 0, null, model);

    String paginationBaseUrl = (String) model.getAttribute("paginationBaseUrl");
    assertNotNull(paginationBaseUrl);
    assertTrue(
        paginationBaseUrl.contains("clientId=other"),
        "pagination must preserve the client filter: " + paginationBaseUrl);
  }

  @Test
  void bankTab_offersAndForwardsTheClientFilterToo() {
    Model model = new ConcurrentModel();
    when(backendApiClient.get(any(String.class), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(), 0, 50, 0, 0, List.of()));

    controller.auditLog("BANK", null, null, null, null, "basetool-android", 0, null, model);

    assertEquals("basetool-android", model.getAttribute("filterClientId"));
    assertFalse(((List<String>) model.getAttribute("clientIds")).isEmpty());
    ArgumentCaptor<String> uri = ArgumentCaptor.forClass(String.class);
    verify(backendApiClient).get(uri.capture(), anyTypeRef());
    assertTrue(
        uri.getValue().contains("clientId=basetool-android"),
        "the bank endpoint takes a clientId parameter now: " + uri.getValue());
    assertTrue(((String) model.getAttribute("paginationBaseUrl")).contains("clientId="));
  }

  @Test
  void bankTab_carriesTheRowsClientThroughTheAdapter() {
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
            "+100 aUEC",
            "basetool-android");
    when(backendApiClient.get(contains("/api/v1/bank/admin/audit"), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(bankRow), 0, 50, 1, 1, List.of()));

    controller.auditLog("BANK", null, null, null, null, null, 0, null, model);

    PageResponse<AuditRowView> events = (PageResponse<AuditRowView>) model.getAttribute("events");
    assertNotNull(events);
    assertEquals("basetool-android", events.content().getFirst().clientId());
  }

  @Test
  void everyOfferedClientFilterValue_carriesALabel() throws Exception {
    Model model = new ConcurrentModel();
    when(backendApiClient.get(any(String.class), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(), 0, 50, 0, 0, List.of()));
    controller.auditLog("INVENTORY", null, null, null, null, null, 0, null, model);

    List<String> clientIds = (List<String>) model.getAttribute("clientIds");
    assertNotNull(clientIds);
    assertTrue(clientIds.contains("basetool-android"), "the app must be filterable");
    assertTrue(clientIds.contains("other"), "an unrecognised client must be filterable");
    assertTrue(clientIds.contains("none"), "a system write must be filterable");
    Properties labels = loadDefaultBundle();
    for (String clientId : clientIds) {
      assertNotNull(
          labels.getProperty("admin.audit.client." + clientId),
          "missing admin.audit.client." + clientId + " label for an offered filter value");
    }
  }

  @Test
  void backendFailure_setsErrorAttribute() {
    Model model = new ConcurrentModel();
    when(backendApiClient.get(any(String.class), anyTypeRef()))
        .thenThrow(new RuntimeException("down"));

    controller.auditLog("JOB_ORDER", null, null, null, null, null, 0, null, model);

    assertEquals("admin.audit.error.load", model.getAttribute("error"));
  }
}
