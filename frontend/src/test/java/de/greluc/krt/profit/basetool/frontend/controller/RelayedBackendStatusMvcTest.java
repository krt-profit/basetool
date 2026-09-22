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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.greluc.krt.profit.basetool.frontend.config.AppHttpProperties;
import de.greluc.krt.profit.basetool.frontend.exception.GlobalExceptionHandler;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.IngestHandoffService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Pins that every proxy relay which forwards a backend refusal as {@code new
 * ResponseStatusException(e.getStatusCode(), …)} answers the caller with the backend's status, not
 * with {@code 500} (APPSEC-11, REQ-OBS-001).
 *
 * <p>Before {@code GlobalExceptionHandler#handleResponseStatus} existed, the advice's {@code
 * Exception} catch-all ran ahead of Spring's own {@code ResponseStatusExceptionResolver}, so a
 * backend {@code 409} relayed by any of these fourteen call sites became a {@code 500} for the
 * browser and an {@code ERROR} line with a stack trace in the log — the unit tests around each
 * relay were green throughout, because they assert the thrown exception's status and never dispatch
 * it through the advice. This test does: each relay is mounted in a standalone {@link MockMvc}
 * together with the real advice, and {@link MockWebServer} plays the backend.
 */
class RelayedBackendStatusMvcTest {

  private static final String FROM = "2026-01-01T00:00:00Z";
  private static final String TO = "2026-02-01T00:00:00Z";

  private MockWebServer backend;
  private WebClient webClient;
  private ListAppender<ILoggingEvent> adviceLog;
  private Logger adviceLogger;

  @BeforeEach
  void setUp() throws Exception {
    backend = new MockWebServer();
    backend.start();
    webClient = WebClient.builder().baseUrl(backend.url("/").toString()).build();
    adviceLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    adviceLog = new ListAppender<>();
    adviceLog.start();
    adviceLogger.addAppender(adviceLog);
  }

  @AfterEach
  void tearDown() throws Exception {
    adviceLogger.detachAppender(adviceLog);
    backend.shutdown();
  }

  /**
   * One row per relay call site: a name, the controller built around the test's {@link WebClient},
   * and the browser request that reaches the relay.
   *
   * @return the fourteen relay call sites
   */
  static Stream<Arguments> relays() {
    UUID id = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    return Stream.of(
        relay(
            "AdminP4kImportPageController#enqueuePreview",
            wc -> new AdminP4kImportPageController(wc, mock(BackendApiClient.class)),
            multipart("/admin/p4k-import/jobs").file(upload())),
        relay(
            "AdminPersonalBlueprintsPageController#previewImport",
            wc -> new AdminPersonalBlueprintsPageController(mock(BackendApiClient.class), wc),
            multipart("/admin/personal-blueprints/" + id + "/import/preview").file(upload())),
        relay(
            "AuditReportProxyController#downloadAuditLog",
            AuditReportProxyController::new,
            get("/api/proxy/audit/BANK/export").param("from", FROM).param("to", TO)),
        relay(
            "AuditReportProxyController#purgeAuditLog",
            AuditReportProxyController::new,
            delete("/api/proxy/audit/BANK").param("before", FROM)),
        relay(
            "BankReportProxyController#downloadStatement",
            BankReportProxyController::new,
            get("/api/proxy/bank/accounts/" + id + "/statement")
                .param("from", FROM)
                .param("to", TO)),
        relay(
            "DataExportProxyController#json",
            wc -> new DataExportProxyController(wc, exportTimeouts()),
            get("/api/proxy/me/export/json")),
        relay(
            "HangarDeleteAllProxyController#deleteAllShips",
            HangarDeleteAllProxyController::new,
            delete("/hangar/ships/all")),
        relay(
            "HangarImportProxyController#importShips",
            wc -> new HangarImportProxyController(wc, new StaticMessageSource()),
            multipart("/hangar/import/ships").file(upload())),
        relay(
            "InventoryDeleteAllProxyController#deleteAllGlobalInventory",
            InventoryDeleteAllProxyController::new,
            delete("/inventory/all")),
        relay(
            "JobOrderHandoverReportProxyController#downloadHandoverReport",
            JobOrderHandoverReportProxyController::new,
            get("/api/v1/orders/" + id + "/handovers/" + other + "/report")),
        relay(
            "JobOrderHandoverReportProxyController#downloadItemHandoverReport",
            JobOrderHandoverReportProxyController::new,
            get("/api/v1/orders/" + id + "/item-handovers/" + other + "/report")),
        relay(
            "JobOrderHandoverReportProxyController#previewHandoverReport",
            JobOrderHandoverReportProxyController::new,
            post("/api/v1/orders/" + id + "/handovers/report/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")),
        relay(
            "OrgUnitBankProxyController#downloadStatement",
            wc -> new OrgUnitBankProxyController(mock(BackendApiClient.class), wc),
            get("/api/proxy/org-units/bank/accounts/" + id + "/statement")
                .param("from", FROM)
                .param("to", TO)),
        relay(
            "PersonalBlueprintImportProxyController#preview",
            wc ->
                new PersonalBlueprintImportProxyController(
                    wc, mock(BackendApiClient.class), mock(IngestHandoffService.class)),
            multipart("/personal-inventory/blueprints/import/preview").file(upload())));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("relays")
  void aBackendConflictReachesAnAjaxCallerAs409NotAs500(
      String name,
      Function<WebClient, Object> controller,
      AbstractMockHttpServletRequestBuilder<?> request)
      throws Exception {
    backend.enqueue(
        new MockResponse()
            .setResponseCode(409)
            .setHeader("Content-Type", "application/problem+json")
            .setBody("{\"code\":\"CONFLICT\",\"status\":409}"));

    mockMvc(controller)
        .perform(request.header("X-Requested-With", "XMLHttpRequest"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.code").value("CONFLICT"));

    assertEquals(1, backend.getRequestCount(), name + " must have reached the backend once");
    assertTrue(
        adviceLog.list.stream().noneMatch(e -> e.getLevel() == Level.ERROR),
        name + ": a relayed 4xx must not be logged at ERROR (REQ-OBS-001)");
  }

  @Test
  void aBackend404OnADownloadNavigationRendersTheErrorPageWith404() throws Exception {
    backend.enqueue(new MockResponse().setResponseCode(404));

    mockMvc(wc -> new DataExportProxyController(wc, exportTimeouts()))
        .perform(get("/api/proxy/me/export/pdf"))
        .andExpect(status().isNotFound());
  }

  @Test
  void aBackend503StaysA503AndIsTheOneFaultLoggedAtError() throws Exception {
    backend.enqueue(new MockResponse().setResponseCode(503));

    mockMvc(HangarDeleteAllProxyController::new)
        .perform(delete("/hangar/ships/all").header("X-Requested-With", "XMLHttpRequest"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));

    assertTrue(
        adviceLog.list.stream().anyMatch(e -> e.getLevel() == Level.ERROR),
        "a relayed 5xx is a server fault and is logged at ERROR");
  }

  @Test
  void anOversizedBlueprintExportIsRefusedWith413WithoutABackendCall() throws Exception {
    MockMultipartFile oversized =
        new MockMultipartFile(
            "file",
            "blueprints.json",
            "application/json",
            new byte[(int) PersonalBlueprintImportProxyController.MAX_EXPORT_BYTES + 1]);

    mockMvc(
            wc ->
                new PersonalBlueprintImportProxyController(
                    wc, mock(BackendApiClient.class), mock(IngestHandoffService.class)))
        .perform(
            multipart("/personal-inventory/blueprints/import/preview")
                .file(oversized)
                .header("X-Requested-With", "XMLHttpRequest"))
        .andExpect(status().isContentTooLarge())
        .andExpect(jsonPath("$.code").value("UPLOAD_TOO_LARGE"));

    assertEquals(0, backend.getRequestCount(), "an oversized upload must not be relayed");
  }

  private MockMvc mockMvc(Function<WebClient, Object> controller) {
    return MockMvcBuilders.standaloneSetup(controller.apply(webClient))
        .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
        .build();
  }

  private static Arguments relay(
      String name,
      Function<WebClient, Object> controller,
      AbstractMockHttpServletRequestBuilder<?> request) {
    return Arguments.of(name, controller, request);
  }

  private static MockMultipartFile upload() {
    return new MockMultipartFile(
        "file", "upload.json", "application/json", "{}".getBytes(StandardCharsets.UTF_8));
  }

  private static AppHttpProperties exportTimeouts() {
    AppHttpProperties properties = mock(AppHttpProperties.class);
    when(properties.exportResponseTimeout()).thenReturn(Duration.ofSeconds(5));
    return properties;
  }
}
