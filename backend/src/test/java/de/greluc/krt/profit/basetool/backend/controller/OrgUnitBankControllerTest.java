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

package de.greluc.krt.profit.basetool.backend.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.BankAccountStatus;
import de.greluc.krt.profit.basetool.backend.model.BankAccountType;
import de.greluc.krt.profit.basetool.backend.model.BankBookingRequestStatus;
import de.greluc.krt.profit.basetool.backend.model.BankBookingRequestType;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.dto.BankBookingRequestDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitBankAccountSettingsDto;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitBankBalanceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.request.CancelBankBookingRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.CreateBankBookingRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.SetBankApprovalLimitRequest;
import de.greluc.krt.profit.basetool.backend.service.OrgUnitBankAccessService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Thin delegation tests for {@link OrgUnitBankController}. The scope / balance behaviour is pinned
 * by {@link de.greluc.krt.profit.basetool.backend.service.OrgUnitBankAccessServiceTest}; this class
 * only verifies the handler wiring and that the balance-only response shape is relayed unaltered.
 */
@ExtendWith(MockitoExtension.class)
class OrgUnitBankControllerTest {

  @Mock private OrgUnitBankAccessService orgUnitBankAccessService;

  @InjectMocks private OrgUnitBankController controller;

  @Test
  void listOverseenBalances_delegatesToService() {
    OrgUnitBankBalanceDto dto =
        new OrgUnitBankBalanceDto(
            UUID.randomUUID(),
            "KB-0001",
            "IRIDIUM",
            BankAccountStatus.ACTIVE,
            BankAccountType.ORG_UNIT,
            UUID.randomUUID(),
            "IRIDIUM",
            "IRI",
            OrgUnitKind.SQUADRON,
            new BigDecimal("4200"),
            true,
            new BigDecimal("300"),
            List.of(new BigDecimal("3900"), new BigDecimal("4200")),
            new BigDecimal("10000"),
            true,
            null);
    when(orgUnitBankAccessService.listOverseenOrgUnitBalances()).thenReturn(List.of(dto));

    List<OrgUnitBankBalanceDto> result = controller.listOverseenBalances();

    assertEquals(1, result.size());
    assertSame(dto, result.getFirst());
    verify(orgUnitBankAccessService).listOverseenOrgUnitBalances();
  }

  @Test
  void listOverseenBalances_emptyService_returnsEmptyList() {
    when(orgUnitBankAccessService.listOverseenOrgUnitBalances()).thenReturn(List.of());

    assertTrue(controller.listOverseenBalances().isEmpty());
  }

  @Test
  void createBookingRequest_delegatesToService() {
    CreateBankBookingRequest request =
        new CreateBankBookingRequest(
            UUID.randomUUID(), BankBookingRequestType.DEPOSIT, null, new BigDecimal("500"), "note");
    BankBookingRequestDto dto = requestDto();
    when(orgUnitBankAccessService.createBookingRequest(request)).thenReturn(dto);

    assertSame(dto, controller.createBookingRequest(request));
    verify(orgUnitBankAccessService).createBookingRequest(request);
  }

  @Test
  void listOwnBookingRequests_delegatesToService() {
    BankBookingRequestDto dto = requestDto();
    when(orgUnitBankAccessService.listOwnBookingRequests()).thenReturn(List.of(dto));

    assertSame(dto, controller.listOwnBookingRequests().getFirst());
  }

  @Test
  void cancelOwnBookingRequest_delegatesWithVersion() {
    UUID id = UUID.randomUUID();
    BankBookingRequestDto dto = requestDto();
    when(orgUnitBankAccessService.cancelOwnBookingRequest(id, 3L)).thenReturn(dto);

    assertSame(dto, controller.cancelOwnBookingRequest(id, new CancelBankBookingRequest(3L)));
    verify(orgUnitBankAccessService).cancelOwnBookingRequest(id, 3L);
  }

  @Test
  void setRoleApprovalLimit_delegatesToService() {
    UUID id = UUID.randomUUID();
    OrgUnitBankAccountSettingsDto settings =
        new OrgUnitBankAccountSettingsDto(
            id,
            "KB-0001",
            "IRIDIUM",
            BankAccountType.ORG_UNIT,
            OrgUnitKind.SQUADRON,
            null,
            0L,
            false,
            false,
            true,
            true,
            false,
            false,
            List.of(),
            List.of(),
            false,
            false,
            List.of(),
            true,
            null);
    when(orgUnitBankAccessService.setRoleApprovalLimit(id, "ENSIGN", new BigDecimal("1000")))
        .thenReturn(settings);

    assertSame(
        settings,
        controller.setRoleApprovalLimit(
            id, "ENSIGN", new SetBankApprovalLimitRequest(new BigDecimal("1000"))));
    verify(orgUnitBankAccessService).setRoleApprovalLimit(id, "ENSIGN", new BigDecimal("1000"));
  }

  @Test
  void grantOwnerApproval_delegatesToService() {
    UUID id = UUID.randomUUID();
    BankBookingRequestDto dto = requestDto();
    when(orgUnitBankAccessService.grantOwnerApproval(id)).thenReturn(dto);

    assertSame(dto, controller.grantOwnerApproval(id));
    verify(orgUnitBankAccessService).grantOwnerApproval(id);
  }

  @Test
  void listForeignRequests_delegatesToService() {
    BankBookingRequestDto dto = requestDto();
    when(orgUnitBankAccessService.listRequestsForResponsibleAccounts()).thenReturn(List.of(dto));

    assertSame(dto, controller.listForeignRequests().getFirst());
    verify(orgUnitBankAccessService).listRequestsForResponsibleAccounts();
  }

  private static BankBookingRequestDto requestDto() {
    return new BankBookingRequestDto(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "KB-0001",
        "Staffel IRIDIUM",
        UUID.randomUUID(),
        "IRIDIUM",
        "IRI",
        BankBookingRequestType.DEPOSIT,
        new BigDecimal("500"),
        "note",
        null,
        BankBookingRequestStatus.PENDING,
        "requester",
        null,
        null,
        null,
        null,
        null,
        null,
        Instant.now(),
        null,
        null,
        false,
        null,
        null,
        false,
        null,
        false,
        null,
        0L);
  }
}
