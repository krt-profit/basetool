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

package de.greluc.krt.profit.basetool.frontend.bank.client;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendClientHarness;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Pins the requests {@link OrgUnitBankRequestsBackendClient} sends and what it decodes. */
class OrgUnitBankRequestsBackendClientTest {

  private static final String REQUESTS = "/api/v1/bank/org-units/requests";

  private static final String EMPTY_PAGE =
      "{\"content\":[],\"page\":0,\"size\":3,\"totalElements\":0,\"totalPages\":0}";

  private BackendClientHarness backend;
  private OrgUnitBankRequestsBackendClient client;

  @BeforeEach
  void setUp() {
    backend = BackendClientHarness.start();
    client = new OrgUnitBankRequestsBackendClient(backend.backendApiClient());
  }

  @AfterEach
  void tearDown() {
    backend.close();
  }

  @Test
  void readsTheOwnAndTheForeignRequestLists() {
    backend.answerJson("[]");
    backend.answerJson("[]");

    assertThat(client.own()).isEmpty();
    assertThat(client.foreign()).isEmpty();

    backend.expect("GET", REQUESTS);
    backend.expect("GET", REQUESTS + "/foreign");
  }

  @Test
  void sendsTheSizeAndEachStateOfThePage() {
    backend.answerJson(EMPTY_PAGE);
    backend.answerJson(EMPTY_PAGE);

    assertThat(client.ownPage(List.of("CONFIRMED", "REJECTED"), 3)).isNotNull();
    assertThat(client.ownPage(List.of(), 5)).isNotNull();

    backend.expect("GET", REQUESTS + "/page?size=3&status=CONFIRMED&status=REJECTED");
    backend.expect("GET", REQUESTS + "/page?size=5");
  }

  @Test
  void decodesThePageTotal() {
    backend.answerJson(
        "{\"content\":[],\"page\":0,\"size\":3,\"totalElements\":7,\"totalPages\":3}");

    assertThat(client.ownPage(List.of("PENDING"), 3).totalElements()).isEqualTo(7);
  }
}
