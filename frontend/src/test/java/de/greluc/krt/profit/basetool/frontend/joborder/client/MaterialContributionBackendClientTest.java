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

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendClientHarness;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Pins authoritative fresh-provision choices at the shared backend transport seam. */
class MaterialContributionBackendClientTest {
  private static final UUID ORDER = UUID.fromString("12345678-1234-1234-1234-123456789012");
  private static final UUID MATERIAL = UUID.fromString("23456789-1234-1234-1234-123456789012");
  private static final UUID CLAIM = UUID.fromString("34567890-1234-1234-1234-123456789012");
  private BackendClientHarness backend;
  private MaterialContributionBackendClient client;

  @BeforeEach
  void setUp() {
    backend = BackendClientHarness.start();
    client = new MaterialContributionBackendClient(backend.backendApiClient());
  }

  @AfterEach
  void tearDown() {
    backend.close();
  }

  @Test
  void declaredMaterialAndQualityReadTypedCurrentPromiseChoices() {
    backend.answerJson(
        """
        [{"claimId":"34567890-1234-1234-1234-123456789012","claimVersion":6,
          "claimingOrgUnit":{"id":"45678901-1234-1234-1234-123456789012",
                             "name":"Permitted Staffel","shorthand":"A"},
          "openAmount":3.125,"minQuality":650,"wholePieces":false}]
        """);

    assertThat(client.provisionOptions(ORDER, MATERIAL, 750))
        .singleElement()
        .satisfies(choice -> {
          assertThat(choice.claimId()).isEqualTo(CLAIM);
          assertThat(choice.claimVersion()).isEqualTo(6L);
          assertThat(choice.claimingOrgUnit().name()).isEqualTo("Permitted Staffel");
          assertThat(choice.openAmount()).isEqualTo(3.125);
          assertThat(choice.minQuality()).isEqualTo(650);
          assertThat(choice.wholePieces()).isFalse();
        });
    backend.expect(
        "GET",
        "/api/v1/orders/"
            + ORDER
            + "/claims/provision-options?materialId="
            + MATERIAL
            + "&quality=750");
  }

  @Test
  void deniedConcreteOrderReadRetainsTheBackendForbiddenStatus() {
    backend.answerProblem(403, "FORBIDDEN");

    assertThatThrownBy(() -> client.provisionOptions(ORDER, MATERIAL, 650))
        .isInstanceOfSatisfying(
            BackendServiceException.class,
            failure -> assertThat(failure.getStatusCode()).isEqualTo(403));
    backend.expect(
        "GET",
        "/api/v1/orders/"
            + ORDER
            + "/claims/provision-options?materialId="
            + MATERIAL
            + "&quality=650");
  }
}
