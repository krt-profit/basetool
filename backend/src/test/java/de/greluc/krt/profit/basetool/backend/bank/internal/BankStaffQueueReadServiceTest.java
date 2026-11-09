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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.greluc.krt.profit.basetool.backend.orgunit.api.BereichRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The bank-staff queue read (REQ-BXCH-005) for a member it has nothing for. */
@ExtendWith(MockitoExtension.class)
class BankStaffQueueReadServiceTest {

  @Mock private BankAccountRepository accountRepository;
  @Mock private BankAccountViewGrantRepository viewGrantRepository;
  @Mock private BankAccountApprovalLimitRepository approvalLimitRepository;
  @Mock private BereichRepository bereichRepository;
  @Mock private OrgUnitRepository orgUnitRepository;
  @Mock private BankBookingRequestRepository requestRepository;
  @Mock private BankPostingRepository postingRepository;

  private BankStaffQueueReadService queue;

  @BeforeEach
  void setUp() {
    queue =
        new BankStaffQueueReadService(
            new BankAccessRules(
                accountRepository,
                viewGrantRepository,
                approvalLimitRepository,
                bereichRepository,
                orgUnitRepository),
            requestRepository,
            postingRepository);
  }

  @Test
  void aMemberWithoutBankRoleGrantOrHeldAccount_isRefusedWithoutLoadingTheRequests() {
    assertThat(queue.snapshot(TestBankActor.member(UUID.randomUUID()).build(), null, null, 10))
        .isEmpty();

    verify(requestRepository, never()).findAllByStatus(any());
  }
}
