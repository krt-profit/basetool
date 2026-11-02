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
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Pins the request {@link OrgUnitBankAccountsBackendClient} sends and the page it decodes. */
class OrgUnitBankAccountsBackendClientTest {

  private static final UUID ID = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");

  private BackendClientHarness backend;
  private OrgUnitBankAccountsBackendClient client;

  @BeforeEach
  void setUp() {
    backend = BackendClientHarness.start();
    client = new OrgUnitBankAccountsBackendClient(backend.backendApiClient());
  }

  @AfterEach
  void tearDown() {
    backend.close();
  }

  @Test
  void asksForPageZeroWithTheRowCountAsSizeAndDecodesThePage() {
    backend.answerJson(
        "{\"content\":[{\"accountId\":\""
            + ID
            + "\",\"accountNo\":\"KB-1\",\"accountName\":\"KRT\",\"type\":\"CARTEL\","
            + "\"balance\":1500,\"balanceTarget\":3000,\"delta30d\":-20,\"sparkline\":[1,2]}],"
            + "\"page\":0,\"size\":3,\"totalElements\":7,\"totalPages\":3}");

    var page = client.firstPage(3);

    assertThat(page.totalElements()).isEqualTo(7);
    assertThat(page.content())
        .singleElement()
        .satisfies(account -> {
          assertThat(account.accountNo()).isEqualTo("KB-1");
          assertThat(account.balanceTarget()).isEqualByComparingTo("3000");
        });
    backend.expect("GET", "/api/v1/bank/org-units/accounts/visible?page=0&size=3");
  }

  @Test
  void answersNothingWhenTheBackendSendsNoBody() {
    backend.answerEmpty();

    assertThat(client.firstPage(3)).isNull();
  }
}
