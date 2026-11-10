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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.frontend.inventory.model.InventoryRowVersionDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.MaterialProvisionCorrectionDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendClientHarness;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Exercises contribution HTTP contracts against an external backend stub. */
class JobOrderContributionBackendClientTest {
  private static final UUID ORDER = UUID.fromString("caa631f2-c5ed-41b3-8ad9-8b458e967d93");
  private static final UUID CREDIT = UUID.fromString("a1a631f2-c5ed-41b3-8ad9-8b458e967d93");
  private BackendClientHarness backend;
  private JobOrderBackendClient client;

  @BeforeEach
  void setUp() {
    backend = BackendClientHarness.start();
    client = new JobOrderBackendClient(backend.backendApiClient());
  }

  @AfterEach
  void tearDown() {
    backend.close();
  }

  @Test
  void historicalAttributionCarriesNoInventoryWriteSnapshot() {
    backend.answerJson("[]");
    assertThat(
            client.correctProvision(
                ORDER,
                CREDIT,
                new MaterialProvisionCorrectionDto(
                    "ATTRIBUTION", 2L, 7L, 3d, ORDER, 12L, List.of(), null)))
        .isEmpty();
    backend.expect(
        "POST",
        "/api/v1/orders/" + ORDER + "/claims/provisions/" + CREDIT + "/correction",
        "{\"kind\":\"ATTRIBUTION\",\"version\":2,\"claimVersion\":7,\"amount\":3.0,\"targetClaimId\":\""
            + ORDER
            + "\",\"targetClaimVersion\":12,\"stockVersions\":[],\"targetStockRowId\":null}");
  }

  @Test
  void quantityIncreaseNamesTheExplicitConnectedRowAndItsVersion() {
    backend.answerJson("[]");
    client.correctProvision(
        ORDER,
        CREDIT,
        new MaterialProvisionCorrectionDto(
            "QUANTITY",
            2L,
            7L,
            4d,
            null,
            null,
            List.of(new InventoryRowVersionDto(ORDER, 9L)),
            ORDER));
    backend.expect(
        "POST",
        "/api/v1/orders/" + ORDER + "/claims/provisions/" + CREDIT + "/correction",
        "{\"kind\":\"QUANTITY\",\"version\":2,\"claimVersion\":7,\"amount\":4.0,\"targetClaimId\":null,\"targetClaimVersion\":null,\"stockVersions\":[{\"id\":\""
            + ORDER
            + "\",\"version\":9}],\"targetStockRowId\":\""
            + ORDER
            + "\"}");
  }

  @Test
  void historicalTargetsRemainIndependentOfFreshClaimingOptions() {
    backend.answerJson(
        "[{\"claimId\":\""
            + ORDER
            + "\",\"claimVersion\":12,\"ended\":true,\"openAmount\":0,\"maxAmount\":3}]");
    var targets = client.attributionOptions(ORDER, CREDIT);
    assertThat(targets).hasSize(1);
    assertThat(targets.getFirst().ended()).isTrue();
    assertThat(targets.getFirst().claimVersion()).isEqualTo(12L);
    backend.expect(
        "GET", "/api/v1/orders/" + ORDER + "/claims/provisions/" + CREDIT + "/attribution-options");
  }

  @Test
  void concreteOrderReadDenialIsNotTurnedIntoEmptyHistory() {
    backend.answerProblem(403, "ACCESS_DENIED");
    assertThatThrownBy(() -> client.contributionHistory(ORDER))
        .isInstanceOfSatisfying(
            BackendServiceException.class,
            failure -> assertThat(failure.getStatusCode()).isEqualTo(403));
    backend.expect("GET", "/api/v1/orders/" + ORDER + "/claims/history");
  }

  @Test
  void historyDecodesCreditedSubsetEvidenceAndASeparateCapacityReduction() {
    backend.answerJson(
        """
        [{"id":"%s","version":7,"openAmount":0,"creditedAmount":3,
          "changes":[{"reason":"CAPACITY_REDUCED","previousAmount":4,"amount":1,
            "creditDelta":0,"recordedAt":"2026-11-01T09:00:00Z"}],
          "provisions":[{"id":"%s","version":2,"amount":3,"quantityAmount":3,
            "providedAt":"2026-11-01T08:00:00Z","canCorrect":true,"evidence":"CREDITED_SUBSET"}]}]
        """
            .formatted(ORDER, CREDIT));
    var history = client.contributionHistory(ORDER).getFirst();
    assertThat(history.provisions().getFirst().evidence())
        .isEqualTo(
            de.greluc.krt.profit.basetool.frontend.joborder.model.MaterialProvisionEvidence
                .CREDITED_SUBSET);
    assertThat(history.changes().getFirst().reason())
        .isEqualTo(
            de.greluc.krt.profit.basetool.frontend.joborder.model.MaterialContributionChange.Reason
                .CAPACITY_REDUCED);
    assertThat(history.changes().getFirst().creditDelta()).isZero();
    backend.expect("GET", "/api/v1/orders/" + ORDER + "/claims/history");
  }
}
