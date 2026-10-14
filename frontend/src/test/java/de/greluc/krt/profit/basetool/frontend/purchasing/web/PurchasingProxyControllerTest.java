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

package de.greluc.krt.profit.basetool.frontend.purchasing.web;

import static de.greluc.krt.profit.basetool.frontend.purchasing.PurchasingFixtures.deal;
import static de.greluc.krt.profit.basetool.frontend.purchasing.PurchasingFixtures.delegate;
import static de.greluc.krt.profit.basetool.frontend.purchasing.PurchasingFixtures.partner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.purchasing.client.PurchasingBackendClient;
import de.greluc.krt.profit.basetool.frontend.purchasing.client.PurchasingBackendClient.PartnerAction;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.AcceptDeliveryRequest;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.AppointDelegateRequest;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.CreateDealRequest;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.CreatePartnerRequest;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.DealDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.DelegateDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.PartnerDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.PausePurchasingRequest;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.PauseStateDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.PurchasingRefDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.PurchasingSettingsDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.PurchasingVersionRequest;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.RecordDeliveryRequest;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.RequestTrancheRequest;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.ResumePurchasingRequest;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.RiskClassDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.SettleDealRequest;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.UpdateDealRequest;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.UpdateDelegateLimitRequest;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.UpdatePartnerRequest;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.UpdatePurchasingSettingsRequest;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.UpdateRiskClassEntry;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.UpdateRiskClassesRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

/** Unit tests for {@link PurchasingProxyController}: forwarding, downloads and month checks. */
@ExtendWith(MockitoExtension.class)
class PurchasingProxyControllerTest {

  private static final UUID ID = UUID.fromString("9c8b7a6f-5e4d-4c3b-8a2f-1e0d9c8b7a6f");
  private static final UUID OTHER = UUID.fromString("3f2a1b4c-5d6e-4f70-8a9b-0c1d2e3f4a5b");

  @Mock private PurchasingBackendClient purchasingClient;

  private PurchasingProxyController controller;

  @BeforeEach
  void setUp() {
    controller = new PurchasingProxyController(purchasingClient);
  }

  private static void assertStatus(Throwable thrown, HttpStatus expected) {
    assertThat(thrown)
        .isInstanceOfSatisfying(
            ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(expected));
  }

  @Test
  void aNewDraftDealIsForwardedToTheClient() {
    CreateDealRequest request =
        new CreateDealRequest(ID, OTHER, 10.0, 1_250L, "ADVANCE", null, false, null);
    DealDto created = deal().status("DRAFT").build();
    when(purchasingClient.createDeal(request)).thenReturn(created);

    assertThat(controller.createDeal(request)).isSameAs(created);
  }

  @Test
  void aDraftEditAndACancellationCarryTheDealIdAndTheEchoedVersion() {
    UpdateDealRequest edit =
        new UpdateDealRequest(OTHER, 8.0, 1_300L, "PARTIAL", "B", true, null, 3L);
    PurchasingVersionRequest cancel = new PurchasingVersionRequest(4L);
    DealDto edited = deal().status("DRAFT").build();
    DealDto cancelled = deal().status("CANCELLED").build();
    when(purchasingClient.updateDeal(ID, edit)).thenReturn(edited);
    when(purchasingClient.cancelDeal(ID, cancel)).thenReturn(cancelled);

    assertThat(controller.updateDeal(ID, edit)).isSameAs(edited);
    assertThat(controller.cancelDeal(ID, cancel)).isSameAs(cancelled);
  }

  @Test
  void trancheDeliveryAndSettlementRequestsReachTheirOwnClientCalls() {
    RequestTrancheRequest tranche = new RequestTrancheRequest("ADVANCE", 5_000L);
    RecordDeliveryRequest delivery = new RecordDeliveryRequest(2.5, OTHER);
    SettleDealRequest settlement = new SettleDealRequest(11_000L, 500L, "FRAUD", 2L);
    DealDto withTranche = deal().build();
    DealDto withDelivery = deal().build();
    DealDto settled = deal().status("SETTLED").build();
    when(purchasingClient.requestTranche(ID, tranche)).thenReturn(withTranche);
    when(purchasingClient.recordDelivery(ID, delivery)).thenReturn(withDelivery);
    when(purchasingClient.settle(ID, settlement)).thenReturn(settled);

    assertThat(controller.requestTranche(ID, tranche)).isSameAs(withTranche);
    assertThat(controller.recordDelivery(ID, delivery)).isSameAs(withDelivery);
    assertThat(controller.settle(ID, settlement)).isSameAs(settled);
  }

  @Test
  void theDecisionOnADeliveryNamesBothTheDealAndTheDelivery() {
    AcceptDeliveryRequest request = new AcceptDeliveryRequest(4.0, 1.0, "DAMAGED", 700, OTHER, 2L);
    DealDto decided = deal().build();
    when(purchasingClient.acceptDelivery(ID, OTHER, request)).thenReturn(decided);

    assertThat(controller.acceptDelivery(ID, OTHER, request)).isSameAs(decided);
  }

  @Test
  void closingTheDeliveryForwardsOnlyTheDealId() {
    DealDto closed = deal().build();
    when(purchasingClient.closeDelivery(ID)).thenReturn(closed);

    assertThat(controller.closeDelivery(ID)).isSameAs(closed);
  }

  @Test
  void aNewAndAnEditedPartnerAreForwardedToTheClient() {
    CreatePartnerRequest create =
        new CreatePartnerRequest("ORG", "Orion", "ORT", "DISCORD", null, null, "DIPLOMACY");
    UpdatePartnerRequest edit =
        new UpdatePartnerRequest("Orion", "ORT", "RSI", "cash only", null, "HB", 5L);
    PartnerDto created = partner("Orion", "ORG", "ACTIVE", false, null, null);
    PartnerDto edited = partner("Orion", "ORG", "ACTIVE", false, "cash only", null);
    when(purchasingClient.createPartner(create)).thenReturn(created);
    when(purchasingClient.updatePartner(ID, edit)).thenReturn(edited);

    assertThat(controller.createPartner(create)).isSameAs(created);
    assertThat(controller.updatePartner(ID, edit)).isSameAs(edited);
  }

  @Test
  void blockUnblockAndEraseSelectTheirOwnPartnerAction() {
    PurchasingVersionRequest request = new PurchasingVersionRequest(5L);
    PartnerDto blocked = partner("Orion", "ORG", "BLOCKED", false, null, null);
    PartnerDto active = partner("Orion", "ORG", "ACTIVE", false, null, null);
    PartnerDto erased = partner("", "INDIVIDUAL", "BLOCKED", true, null, null);
    when(purchasingClient.changePartner(ID, PartnerAction.BLOCK, request)).thenReturn(blocked);
    when(purchasingClient.changePartner(ID, PartnerAction.UNBLOCK, request)).thenReturn(active);
    when(purchasingClient.changePartner(ID, PartnerAction.ERASE, request)).thenReturn(erased);

    assertThat(controller.blockPartner(ID, request)).isSameAs(blocked);
    assertThat(controller.unblockPartner(ID, request)).isSameAs(active);
    assertThat(controller.erasePartner(ID, request)).isSameAs(erased);
  }

  @Test
  void appointmentRevocationAndLimitAreForwardedToTheClient() {
    AppointDelegateRequest appoint =
        new AppointDelegateRequest(OTHER, Instant.parse("2026-12-31T00:00:00Z"), 50_000L);
    PurchasingVersionRequest revoke = new PurchasingVersionRequest(2L);
    UpdateDelegateLimitRequest limit = new UpdateDelegateLimitRequest(75_000L, 2L);
    DelegateDto appointed = delegate("Tester", 50_000L, 50_000L, null);
    DelegateDto revoked =
        delegate("Tester", 50_000L, 50_000L, Instant.parse("2026-09-02T12:00:00Z"));
    DelegateDto limited = delegate("Tester", 75_000L, 75_000L, null);
    when(purchasingClient.appoint(appoint)).thenReturn(appointed);
    when(purchasingClient.revoke(ID, revoke)).thenReturn(revoked);
    when(purchasingClient.setLimit(ID, limit)).thenReturn(limited);

    assertThat(controller.appoint(appoint)).isSameAs(appointed);
    assertThat(controller.revoke(ID, revoke)).isSameAs(revoked);
    assertThat(controller.setLimit(ID, limit)).isSameAs(limited);
  }

  @Test
  void pauseAndResumeAreForwardedToTheClient() {
    PausePurchasingRequest pause = new PausePurchasingRequest(OTHER, "BUDGET");
    ResumePurchasingRequest resume = new ResumePurchasingRequest(OTHER);
    PauseStateDto paused =
        new PauseStateDto(true, "BUDGET", Instant.parse("2026-09-01T12:00:00Z"), List.of());
    PauseStateDto resumed = new PauseStateDto(false, null, null, List.of());
    when(purchasingClient.pause(pause)).thenReturn(paused);
    when(purchasingClient.resume(resume)).thenReturn(resumed);

    assertThat(controller.pause(pause)).isSameAs(paused);
    assertThat(controller.resume(resume)).isSameAs(resumed);
  }

  @Test
  void theAdminSettingsAndRiskClassesAreForwardedToTheClient() {
    UpdatePurchasingSettingsRequest settings =
        new UpdatePurchasingSettingsRequest(
            true, List.of(ID), OTHER, 50_000L, null, "MONTH", 14, List.of(), 4L);
    UpdateRiskClassesRequest classes =
        new UpdateRiskClassesRequest(
            List.of(new UpdateRiskClassEntry("A", 100_000L, 50, false, false, 1L)));
    PurchasingSettingsDto stored =
        new PurchasingSettingsDto(
            true,
            List.of(new PurchasingRefDto(ID, "Profit")),
            OTHER,
            "100",
            "Fund",
            50_000L,
            null,
            "MONTH",
            14,
            List.of(),
            false,
            null,
            5L);
    List<RiskClassDto> table = List.of(new RiskClassDto("A", 100_000L, 50, false, false, 2L));
    when(purchasingClient.updateSettings(settings)).thenReturn(stored);
    when(purchasingClient.updateRiskClasses(classes)).thenReturn(table);

    assertThat(controller.updateSettings(settings)).isSameAs(stored);
    assertThat(controller.updateRiskClasses(classes)).isSameAs(table);
  }

  @Test
  void theCsvExportIsAnAttachmentOfTheBackendBytes() {
    byte[] csv = "month;total\n2026-09;1\n".getBytes(StandardCharsets.UTF_8);
    when(purchasingClient.reportCsv("2026-08", "2026-09")).thenReturn(csv);

    ResponseEntity<byte[]> response = controller.exportReport("2026-08", "2026-09");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEqualTo(csv);
    assertThat(response.getHeaders().getContentType())
        .isEqualTo(new MediaType("text", "csv", StandardCharsets.UTF_8));
    assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
        .startsWith("attachment")
        .contains("purchasing-report.csv");
  }

  @Test
  void anEmptyCsvBodyBecomesAnEmptyFile() {
    when(purchasingClient.reportCsv(null, null)).thenReturn(null);

    ResponseEntity<byte[]> response = controller.exportReport(null, null);

    assertThat(response.getBody()).isEmpty();
  }

  @Test
  void theForumTextIsPlainTextOfTheBackendBody() {
    when(purchasingClient.reportForumText("2026-08", null)).thenReturn("Ankauf: 1");

    ResponseEntity<String> response = controller.forumText("2026-08", null);

    assertThat(response.getBody()).isEqualTo("Ankauf: 1");
    assertThat(response.getHeaders().getContentType())
        .isEqualTo(new MediaType("text", "plain", StandardCharsets.UTF_8));
  }

  @Test
  void anEmptyForumTextBodyBecomesAnEmptyString() {
    when(purchasingClient.reportForumText(null, null)).thenReturn(null);

    assertThat(controller.forumText(null, null).getBody()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"2026-13", "2026-00", "2026-1", "26-09", "September", "2026-09-01"})
  void aMalformedMonthIsRefusedAsABadRequestBeforeTheBackendIsAsked(String month) {
    assertStatus(
        catchThrowable(() -> controller.exportReport(month, null)), HttpStatus.BAD_REQUEST);
    assertStatus(catchThrowable(() -> controller.forumText(null, month)), HttpStatus.BAD_REQUEST);

    verifyNoInteractions(purchasingClient);
  }

  @Test
  void aBlankMonthIsAcceptedAndPassedOn() {
    when(purchasingClient.reportForumText("", " ")).thenReturn("x");

    assertThat(controller.forumText("", " ").getBody()).isEqualTo("x");
  }

  @Test
  void aBackendRefusalOfTheExportKeepsItsStatus() {
    BackendServiceException refused = new BackendServiceException("forbidden", null, 403);
    when(purchasingClient.reportCsv("2026-08", "2026-09")).thenThrow(refused);

    assertThatThrownBy(() -> controller.exportReport("2026-08", "2026-09"))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> {
              assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
              assertThat(e.getCause()).isSameAs(refused);
            });
  }

  @Test
  void aBackendRefusalOfTheForumTextKeepsItsStatus() {
    when(purchasingClient.reportForumText(null, null))
        .thenThrow(new BackendServiceException("unavailable", null, 503));

    assertThatThrownBy(() -> controller.forumText(null, null))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
  }
}
