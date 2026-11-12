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

package de.greluc.krt.profit.basetool.frontend.catalogue.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.P4kReviewApplyRequest;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.P4kReviewBatchMember;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.P4kReviewBatchRequest;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendClientHarness;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pins the grouped read and confirmed-action transport of the P4K review workspace. */
class P4kReviewWorkspaceBackendClientTest {

  private static final String BASE = "/api/v1/catalog/admin/import/p4k/review-workspace";
  private static final String EMPTY_PAGE =
      "{\"content\":[],\"page\":0,\"size\":25,\"totalElements\":0,\"totalPages\":0}";
  private static final UUID GROUP = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
  private static final UUID CASE = UUID.fromString("00000000-0000-4000-8000-0000000000a2");
  private static final UUID TARGET = UUID.fromString("00000000-0000-4000-8000-0000000000a3");

  @Test
  void theWorkspaceReadsSendFiltersAndStableIdentifiers() {
    try (BackendClientHarness backend = BackendClientHarness.start()) {
      P4kReviewWorkspaceBackendClient client =
          new P4kReviewWorkspaceBackendClient(backend.backendApiClient());
      backend.answerJson(EMPTY_PAGE);
      backend.answerJson(EMPTY_PAGE);
      backend.answerJson("{}");

      client.groups("SHIP", "GUID_CONFLICT", "ACTIONABLE", 2, 25);
      client.groupMembers(GROUP, 3, 100);
      client.detail(CASE);

      backend.expect(
          "GET", BASE + "?kind=SHIP&reason=GUID_CONFLICT&readiness=ACTIONABLE&page=2&size=25");
      backend.expect("GET", BASE + "/groups/" + GROUP + "?page=3&size=100");
      backend.expect("GET", BASE + "/" + CASE);
    }
  }

  @Test
  void theWorkspaceWritesSendPreviewedVersionsAndPropagateConflicts() {
    try (BackendClientHarness backend = BackendClientHarness.start()) {
      P4kReviewWorkspaceBackendClient client =
          new P4kReviewWorkspaceBackendClient(backend.backendApiClient());
      backend.answerProblem(409, "CONFLICT");
      backend.answerEmpty();

      assertThatThrownBy(() ->
              client.apply(CASE, new P4kReviewApplyRequest("ATTACH_P4K", TARGET, 7L, null, 6L)))
          .isInstanceOfSatisfying(
              BackendServiceException.class,
              failure -> assertThat(failure.getStatusCode()).isEqualTo(409));
      client.resolveGroup(
          GROUP, new P4kReviewBatchRequest("KEEP", List.of(new P4kReviewBatchMember(CASE, 6L))));

      String action =
          backend
              .expect("POST", BASE + "/" + CASE + "/apply")
              .getBody()
              .readString(StandardCharsets.UTF_8);
      assertThat(action)
          .contains(
              "\"action\":\"ATTACH_P4K\"",
              "\"targetId\":\"" + TARGET + "\"",
              "\"targetVersion\":7",
              "\"version\":6");
      String batch =
          backend
              .expect("POST", BASE + "/groups/" + GROUP + "/resolve")
              .getBody()
              .readString(StandardCharsets.UTF_8);
      assertThat(batch)
          .contains("\"resolution\":\"KEEP\"", "\"id\":\"" + CASE + "\"", "\"version\":6");
    }
  }
}
