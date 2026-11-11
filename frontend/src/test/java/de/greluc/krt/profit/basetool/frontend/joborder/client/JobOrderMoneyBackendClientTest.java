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

package de.greluc.krt.profit.basetool.frontend.joborder.client;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.joborder.model.ReassignJobOrderMoneyDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.SettleJobOrderMoneyDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendClientHarness;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pins the paired purpose and requester-return transport against the external backend seam. */
class JobOrderMoneyBackendClientTest {
  private static final UUID ORDER = UUID.fromString("10b9d783-5190-4fbe-955e-b9310b0f1178");
  private static final UUID HOLDER = UUID.fromString("20b9d783-5190-4fbe-955e-b9310b0f1178");
  private static final UUID DESTINATION = UUID.fromString("30b9d783-5190-4fbe-955e-b9310b0f1178");
  private static final UUID OPERATION = UUID.fromString("40b9d783-5190-4fbe-955e-b9310b0f1178");
  private static final String MOVEMENTS = "/api/v1/orders/" + ORDER + "/money/movements";

  @Test
  void purposePreviewAndSubmissionKeepBothObservedHolderRevisions() {
    try (BackendClientHarness backend = BackendClientHarness.start()) {
      var client = new JobOrderMoneyBackendClient(backend.backendApiClient());
      backend.answerJson(
          """
          {"id":"%s","holders":[{"id":"%s","displayName":"Player","version":2}]}
          """
              .formatted(DESTINATION, HOLDER));
      var destination = client.reassignmentDestination(ORDER, HOLDER, 37);
      assertThat(destination.id()).isEqualTo(DESTINATION);
      assertThat(destination.holders().getFirst().version()).isEqualTo(2);
      String path = MOVEMENTS + "/reassignment";
      backend.expect("GET", path + "/destination?sourceHolderId=" + HOLDER + "&number=37");

      var request =
          new ReassignJobOrderMoneyDto(
              OPERATION, HOLDER, 7L, DESTINATION, HOLDER, 2L, true, "8", false);
      String body =
          """
          {"operationId":"%s","sourceHolderId":"%s","sourceVersion":7,"destinationOrderId":"%s","destinationHolderId":"%s","destinationVersion":2,"confirmSamePerson":true,"amount":"8","bankHandling":false}
          """
              .formatted(OPERATION, HOLDER, DESTINATION, HOLDER)
              .strip();
      backend.answerJson(
          """
          {"amount":"8","fee":"0","provision":"0","sourceAfter":"2","destinationAfter":"10"}
          """);
      assertThat(client.previewReassignment(ORDER, request).destinationAfter()).isEqualTo("10");
      backend.expect("POST", path + "/preview", body);
      backend.answerJson("{\"operationId\":\"" + OPERATION + "\"}");
      assertThat(client.reassign(ORDER, request).operationId()).isEqualTo(OPERATION);
      backend.expect("POST", path, body);
    }
  }

  @Test
  void requesterReturnRelaysItsNetAndFeeFundingAsSeparateTerms() {
    try (BackendClientHarness backend = BackendClientHarness.start()) {
      var client = new JobOrderMoneyBackendClient(backend.backendApiClient());
      var request =
          new SettleJobOrderMoneyDto(
              OPERATION,
              "REQUESTER_RETURN",
              null,
              null,
              HOLDER,
              DESTINATION,
              3L,
              null,
              null,
              1L,
              "2000000",
              "0.005",
              "10000",
              false);
      String path = MOVEMENTS + "/settlement";
      String body =
          """
          {"operationId":"%s","kind":"REQUESTER_RETURN","sourceHolderId":null,"sourceVersion":null,"recipientId":"%s","destinationHolderId":"%s","destinationVersion":3,"destinationUserId":null,"destinationName":null,"targetVersion":1,"amount":"2000000","rate":"0.005","provision":"10000","bankHandling":false}
          """
              .formatted(OPERATION, HOLDER, DESTINATION)
              .strip();
      backend.answerJson(
          "{\"amount\":\"2000000\",\"fee\":\"10000\",\"destinationAfter\":\"2000000\"}");
      var preview = client.previewSettlement(ORDER, request);
      assertThat(preview.fee()).isEqualTo("10000");
      assertThat(preview.destinationAfter()).isEqualTo("2000000");
      backend.expect("POST", path + "/preview", body);
      backend.answerJson("{\"operationId\":\"" + OPERATION + "\"}");
      assertThat(client.settle(ORDER, request).operationId()).isEqualTo(OPERATION);
      backend.expect("POST", path, body);
    }
  }
}
