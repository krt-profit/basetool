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

package de.greluc.krt.profit.basetool.frontend.identity.client;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.model.dto.ApproveRegistrationRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.ConsolidateAccountRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.CreateDeletionRequestRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.DecideDeletionRequestRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.LinkRegistrationRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.MembershipDeltaRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.MergeAccountRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.MyBlueprintSharingRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.MyPayoutPreferenceRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.MyRsiHandleRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.RejectRegistrationRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.ReopenRegistrationRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserAttributesUpdateDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserDescriptionRequest;
import de.greluc.krt.profit.basetool.frontend.service.BackendClientHarness;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the requests {@link IdentityBackendClient} sends (plan F3), each the exact URI and body the
 * controllers sent before the client existed.
 */
class IdentityBackendClientTest {

  private static final UUID USER = UUID.fromString("2f1e0d9c-8b7a-4695-a4b3-c2d1e0f9a8b7");
  private static final UUID OTHER = UUID.fromString("7a6b5c4d-3e2f-4a1b-9c8d-7e6f5a4b3c2d");
  private static final UUID SQUADRON = UUID.fromString("0c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f");
  private static final Duration TIMEOUT = Duration.ofSeconds(120);
  private static final String EMPTY_PAGE =
      "{\"content\":[],\"page\":0,\"size\":50,\"totalElements\":0,\"totalPages\":0}";

  private BackendClientHarness backend;
  private IdentityBackendClient client;

  @BeforeEach
  void setUp() {
    backend = BackendClientHarness.start();
    client = new IdentityBackendClient(backend.backendApiClient());
  }

  @AfterEach
  void tearDown() {
    backend.close();
  }

  @Test
  void ownProfileReads() {
    backend.answerJson("{\"id\":\"" + USER + "\",\"version\":4}");
    backend.answerJson("{\"defaultPayoutPreference\":\"DONATE\",\"version\":4}");
    backend.answerJson("{\"shareBlueprintsGlobally\":true,\"version\":4}");
    backend.answerJson("{\"rsiHandle\":\"Valk_RSI\",\"version\":4}");
    backend.answerEmpty();
    backend.answerJson("{\"approvalStatus\":\"PENDING\"}");

    assertThat(client.me().version()).isEqualTo(4L);
    assertThat(client.myPayoutPreference().defaultPayoutPreference()).isEqualTo("DONATE");
    assertThat(client.myBlueprintSharing().shareBlueprintsGlobally()).isTrue();
    assertThat(client.myRsiHandle().rsiHandle()).isEqualTo("Valk_RSI");
    assertThat(client.myDeletionRequest()).isNull();
    assertThat(client.registrationStatus().approvalStatus()).isEqualTo("PENDING");

    backend.expect("GET", "/api/v1/users/me");
    backend.expect("GET", "/api/v1/users/me/payout-preference");
    backend.expect("GET", "/api/v1/users/me/blueprint-sharing");
    backend.expect("GET", "/api/v1/users/me/rsi-handle");
    backend.expect("GET", "/api/v1/users/me/deletion-request");
    backend.expect("GET", "/api/v1/users/me/registration-status");
  }

  @Test
  void ownProfileWrites() {
    backend.answerEmpty();
    backend.answerEmpty();
    backend.answerEmpty();
    backend.answerEmpty();
    backend.answerJson("{\"rsiHandle\":\"New_Handle\",\"version\":8}");

    client.updateMyDescription(new UserDescriptionRequest("Hallo", "Valk", 7L));
    client.updateMyPayoutPreference(new MyPayoutPreferenceRequest("DONATE", 7L));
    client.updateMyBlueprintSharing(new MyBlueprintSharingRequest(true, 7L));
    client.saveMyRsiHandle(new MyRsiHandleRequest("Valk_RSI", 7L));
    assertThat(client.updateMyRsiHandle(new MyRsiHandleRequest("New_Handle", 7L)).version())
        .isEqualTo(8L);

    backend.expect(
        "PUT",
        "/api/v1/users/me/description",
        "{\"description\":\"Hallo\",\"displayName\":\"Valk\",\"version\":7}");
    backend.expect(
        "PUT", "/api/v1/users/me/payout-preference", "{\"preference\":\"DONATE\",\"version\":7}");
    backend.expect(
        "PUT",
        "/api/v1/users/me/blueprint-sharing",
        "{\"shareBlueprintsGlobally\":true,\"version\":7}");
    backend.expect(
        "PUT", "/api/v1/users/me/rsi-handle", "{\"rsiHandle\":\"Valk_RSI\",\"version\":7}");
    backend.expect(
        "PUT", "/api/v1/users/me/rsi-handle", "{\"rsiHandle\":\"New_Handle\",\"version\":7}");
  }

  @Test
  void ownErasureRequest() {
    backend.answerJson(
        "{\"id\":\"" + OTHER + "\",\"status\":\"PENDING\",\"eraseHistoryRequested\":true}");
    backend.answerEmpty();

    assertThat(client.requestDeletion(new CreateDeletionRequestRequest(true)).status())
        .isEqualTo("PENDING");
    client.withdrawDeletionRequest();

    backend.expect("POST", "/api/v1/users/me/deletion-request", "{\"eraseHistory\":true}");
    backend.expect("DELETE", "/api/v1/users/me/deletion-request", null);
  }

  @Test
  void dataExports() {
    byte[] document = {1, 2, 3};
    backend.answerBytes("application/json", document);
    backend.answerBytes("application/pdf", document);
    backend.answerBytes("application/pdf", document);
    backend.answerBytes("application/json", document);

    assertThat(client.myExportJson(TIMEOUT)).isEqualTo(document);
    assertThat(client.myExportPdf(TIMEOUT)).isEqualTo(document);
    assertThat(client.memberExportPdf(USER, TIMEOUT)).isEqualTo(document);
    assertThat(client.memberExportJson(USER, TIMEOUT)).isEqualTo(document);

    backend.expect("GET", "/api/v1/users/me/export");
    backend.expect("GET", "/api/v1/users/me/export/pdf");
    backend.expect("GET", "/api/v1/users/admin/" + USER + "/export/pdf");
    backend.expect("GET", "/api/v1/users/admin/" + USER + "/export");
  }

  @Test
  void memberListsAndSearches() {
    for (int i = 0; i < 6; i++) {
      backend.answerJson(EMPTY_PAGE);
    }

    client.memberPage(2, 25);
    client.memberPage(null, null);
    client.memberSearchPage(0, 25, "John Doe");
    client.memberTypeahead("ali");
    client.userReferences("");
    client.bankUserReferences("ali");

    backend.expect("GET", "/api/v1/users?page=2&size=25&sort=username,asc");
    backend.expect("GET", "/api/v1/users?sort=username,asc");
    backend.expect("GET", "/api/v1/users/search?page=0&size=25&sort=username,asc&query=John%20Doe");
    backend.expect("GET", "/api/v1/users/search?size=1000&sort=username,asc&query=ali");
    backend.expect("GET", "/api/v1/users/search/references?size=51&sort=username,asc&query=");
    backend.expect(
        "GET", "/api/v1/users/search-bank/references?size=51&sort=username,asc&query=ali");
  }

  @Test
  void memberReads() {
    backend.answerJson("{\"id\":\"" + USER + "\"}");
    backend.answerJson("{\"rsiHandle\":null}");
    backend.answerJson("[]");
    backend.answerJson("[]");
    backend.answerJson("{\"memberships\":[]}");

    assertThat(client.user(USER).id()).isEqualTo(USER);
    assertThat(client.userRsiHandle(USER).rsiHandle()).isNull();
    assertThat(client.memberships(USER)).isEmpty();
    assertThat(client.membershipOptions(USER, true)).isEmpty();
    assertThat(client.membershipDetail(USER).memberships()).isEmpty();

    backend.expect("GET", "/api/v1/users/" + USER);
    backend.expect("GET", "/api/v1/users/" + USER + "/rsi-handle");
    backend.expect("GET", "/api/v1/users/" + USER + "/memberships");
    backend.expect("GET", "/api/v1/users/" + USER + "/memberships?allKinds=true");
    backend.expect("GET", "/api/v1/users/" + USER + "/memberships/detail");
  }

  @Test
  void memberWrites() {
    backend.answerEmpty();
    backend.answerJson("{\"memberships\":[]}");
    backend.answerEmpty();
    backend.answerJson("{\"syncedCount\":3}");
    backend.answerJson("{\"id\":\"" + OTHER + "\"}");

    client.updateAttributes(USER, new UserAttributesUpdateDto(5, "Desc", "Valk", 2L, null));
    client.updateMemberships(
        USER,
        new MembershipDeltaRequest(
            List.of(new MembershipDeltaRequest.StaffelChange(SQUADRON, true, null)), null));
    client.deleteUser(USER);
    assertThat(client.syncUsers().syncedCount()).isEqualTo(3);
    assertThat(client.consolidate(USER, new ConsolidateAccountRequest(OTHER, 6L)).id())
        .isEqualTo(OTHER);

    backend.expect(
        "PUT",
        "/api/v1/users/" + USER + "/attributes",
        "{\"rank\":5,\"description\":\"Desc\",\"displayName\":\"Valk\",\"version\":2,"
            + "\"joinDate\":null}");
    backend.expect(
        "PATCH",
        "/api/v1/users/" + USER + "/memberships",
        "{\"staffeln\":[{\"squadronId\":\""
            + SQUADRON
            + "\",\"isLogistician\":true,\"isMissionManager\":null}],\"specialCommands\":null}");
    backend.expect("DELETE", "/api/v1/users/" + USER, null);
    backend.expect("POST", "/api/v1/users/sync", null);
    backend.expect(
        "POST",
        "/api/v1/users/" + USER + "/consolidate",
        "{\"targetUserId\":\"" + OTHER + "\",\"version\":6}");
  }

  @Test
  void registrationQueue() {
    backend.answerJson("[]");
    backend.answerJson("[]");
    for (int i = 0; i < 5; i++) {
      backend.answerJson("{}");
    }

    assertThat(client.pendingRegistrations()).isEmpty();
    assertThat(client.rejectedRegistrations()).isEmpty();
    client.approveRegistration(USER, new ApproveRegistrationRequest(1L));
    client.rejectRegistration(USER, new RejectRegistrationRequest("Unknown", 2L));
    client.reopenRegistration(USER, new ReopenRegistrationRequest(null, 3L));
    client.mergeRegistration(USER, new MergeAccountRequest(OTHER, 4L));
    client.linkRegistration(USER, new LinkRegistrationRequest(OTHER, 5L));

    String base = "/api/v1/users/admin/registrations";
    backend.expect("GET", base);
    backend.expect("GET", base + "?status=REJECTED");
    backend.expect("POST", base + "/" + USER + "/approve", "{\"version\":1}");
    backend.expect("POST", base + "/" + USER + "/reject", "{\"reason\":\"Unknown\",\"version\":2}");
    backend.expect("POST", base + "/" + USER + "/reopen", "{\"reason\":null,\"version\":3}");
    backend.expect(
        "POST", base + "/" + USER + "/merge", "{\"sourceUserId\":\"" + OTHER + "\",\"version\":4}");
    backend.expect(
        "POST", base + "/" + USER + "/link", "{\"targetUserId\":\"" + OTHER + "\",\"version\":5}");
  }

  @Test
  void registrationWritesWithoutABodySendNone() {
    backend.answerJson("{}");

    client.approveRegistration(USER, null);

    backend.expect("POST", "/api/v1/users/admin/registrations/" + USER + "/approve", null);
  }

  @Test
  void erasureQueueAndPersonSearch() {
    backend.answerJson("[]");
    backend.answerJson("{\"id\":\"" + OTHER + "\",\"status\":\"DECLINED\"}");
    backend.answerEmpty();
    backend.answerJson("{\"hits\":[],\"truncated\":false,\"cappedColumns\":[]}");

    assertThat(client.deletionRequests()).isEmpty();
    client.declineDeletionRequest(OTHER, new DecideDeletionRequestRequest(false, "Reason.", 3L));
    client.executeDeletionRequest(OTHER, new DecideDeletionRequestRequest(true, null, null));
    assertThat(client.personSearch("Mueller").truncated()).isFalse();

    backend.expect("GET", "/api/v1/users/admin/deletion-requests");
    backend.expect(
        "POST",
        "/api/v1/users/admin/deletion-requests/" + OTHER + "/decline",
        "{\"grantHistoryErasure\":false,\"note\":\"Reason.\",\"version\":3}");
    backend.expect(
        "POST",
        "/api/v1/users/admin/deletion-requests/" + OTHER + "/execute",
        "{\"grantHistoryErasure\":true,\"note\":null,\"version\":null}");
    backend.expect("GET", "/api/v1/users/admin/person-search?q=Mueller");
  }

  @Test
  void termsOfUse() {
    backend.answerJson("{}");
    backend.answerJson("{}");
    backend.answerJson("{\"accepted\":true,\"currentVersion\":\"v1\"}");
    backend.answerEmpty();
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson("{\"pending\":3,\"termsVersion\":\"v1\"}");

    client.publicTermsDocument();
    client.termsDocument();
    assertThat(client.termsStatus().accepted()).isTrue();
    client.acceptTerms();
    client.termsAcceptances("PENDING", 2, 25);
    assertThat(client.termsPendingCount().pending()).isEqualTo(3L);

    backend.expect("GET", "/api/v1/terms/document");
    backend.expect("GET", "/api/v1/terms/document");
    backend.expect("GET", "/api/v1/terms/status");
    backend.expect("POST", "/api/v1/terms/acceptance", null);
    backend.expect("GET", "/api/v1/terms/admin?filter=PENDING&page=2&size=25&sort=username,asc");
    backend.expect("GET", "/api/v1/terms/admin/pending-count");
  }
}
