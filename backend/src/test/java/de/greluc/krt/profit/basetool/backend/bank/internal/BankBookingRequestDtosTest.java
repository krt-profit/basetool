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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.bank.api.BankBookingRequestType;
import java.math.BigDecimal;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

/** Maps a booking request's destination account to its wire shape (REQ-BANK-023, REQ-BANK-040). */
class BankBookingRequestDtosTest {

  /**
   * An active org-unit account without an owning unit.
   *
   * @param accountNo the account number
   * @param name the display name
   * @return the account
   */
  private static @NotNull BankAccount account(@NotNull String accountNo, @NotNull String name) {
    BankAccount account = new BankAccount();
    account.setId(UUID.randomUUID());
    account.setAccountNo(accountNo);
    account.setName(name);
    account.setType(BankAccountType.ORG_UNIT);
    account.setStatus(BankAccountStatus.ACTIVE);
    return account;
  }

  /**
   * A pending request on the account {@code KB-0001}.
   *
   * @param type the requested movement
   * @return the request
   */
  private static @NotNull BankBookingRequest pending(@NotNull BankBookingRequestType type) {
    BankBookingRequest request = new BankBookingRequest();
    request.setId(UUID.randomUUID());
    request.setAccount(account("KB-0001", "Staffel ALPHA"));
    request.setType(type);
    request.setAmount(new BigDecimal("500"));
    request.setStatus(BankBookingRequestStatus.PENDING);
    request.setRequesterHandle("requester");
    return request;
  }

  /** A transfer carries its destination account's id, number and display name. */
  @Test
  void aTransferNamesItsDestinationAccount() {
    BankAccount destination = account("KB-0002", "Staffel BRAVO");
    BankBookingRequest request = pending(BankBookingRequestType.TRANSFER);
    request.setTargetAccount(destination);

    BankBookingRequestDto dto = BankBookingRequestDtos.of(request, null, null, null);

    assertThat(dto.targetAccountId()).isEqualTo(destination.getId());
    assertThat(dto.targetAccountNo()).isEqualTo("KB-0002");
    assertThat(dto.targetAccountName()).isEqualTo("Staffel BRAVO");
  }

  /** A request without a destination account carries none of the destination's fields. */
  @Test
  void aRequestWithoutADestinationCarriesNone() {
    BankBookingRequestDto dto =
        BankBookingRequestDtos.of(pending(BankBookingRequestType.WITHDRAWAL), null, null, null);

    assertThat(dto.targetAccountId()).isNull();
    assertThat(dto.targetAccountNo()).isNull();
    assertThat(dto.targetAccountName()).isNull();
  }
}
