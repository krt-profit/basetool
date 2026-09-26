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

package de.greluc.krt.profit.basetool.frontend.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.model.dto.BankWipeResetResultDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

/**
 * Unit tests for {@link AdminBankPageController}: the wipe-reset PRG flow and the bank-audit
 * redirect to {@code /admin/audit-log}.
 */
class AdminBankPageControllerTest {

  private BackendApiClient backendApiClient;
  private AdminBankPageController controller;

  @BeforeEach
  void setUp() {
    backendApiClient = mock(BackendApiClient.class);
    controller = new AdminBankPageController(backendApiClient);
  }

  @Test
  void wipeReset_withoutConfirmToken_skipsBackendAndFlagsError() {
    RedirectAttributesModelMap attrs = new RedirectAttributesModelMap();

    String view = controller.wipeReset("nope", attrs);

    assertEquals("redirect:/admin/bank", view);
    assertEquals("admin.bank.wipe.error.confirm", attrs.getFlashAttributes().get("error"));
    verify(backendApiClient, never()).post(any(), any(), any());
  }

  @Test
  void wipeReset_withCounts_flashesResult() {
    RedirectAttributesModelMap attrs = new RedirectAttributesModelMap();
    when(backendApiClient.post(
            eq("/api/v1/bank/admin/wipe-reset"), any(), eq(BankWipeResetResultDto.class)))
        .thenReturn(new BankWipeResetResultDto(5, 12, new BigDecimal("1250")));

    String view = controller.wipeReset("WIPE", attrs);

    assertEquals("redirect:/admin/bank", view);
    BankWipeResetResultDto result =
        (BankWipeResetResultDto) attrs.getFlashAttributes().get("wipeResult");
    assertNotNull(result);
    assertEquals(5, result.accountsReset());
  }

  @Test
  void wipeReset_zeroCounts_flashesNoop() {
    RedirectAttributesModelMap attrs = new RedirectAttributesModelMap();
    when(backendApiClient.post(
            eq("/api/v1/bank/admin/wipe-reset"), any(), eq(BankWipeResetResultDto.class)))
        .thenReturn(new BankWipeResetResultDto(0, 0, BigDecimal.ZERO));

    controller.wipeReset("WIPE", attrs);

    assertEquals(Boolean.TRUE, attrs.getFlashAttributes().get("wipeNoop"));
  }

  @Test
  void wipeReset_backendFailure_flashesError() {
    RedirectAttributesModelMap attrs = new RedirectAttributesModelMap();
    when(backendApiClient.post(
            eq("/api/v1/bank/admin/wipe-reset"), any(), eq(BankWipeResetResultDto.class)))
        .thenThrow(new RuntimeException("boom"));

    controller.wipeReset("WIPE", attrs);

    assertEquals("admin.bank.wipe.error.failed", attrs.getFlashAttributes().get("error"));
  }

  @Test
  void bankAuditRedirect_redirectsToUnifiedAuditLogBankTab() {
    assertEquals("redirect:/admin/audit-log?domain=BANK", controller.bankAuditRedirect());
  }

  @Test
  void bankAdmin_returnsView() {
    assertEquals("admin/bank", controller.bankAdmin());
  }

  @Test
  void wipeReset_emptyBackendBody_treatedAsNoop() {
    RedirectAttributesModelMap attrs = new RedirectAttributesModelMap();
    when(backendApiClient.post(
            eq("/api/v1/bank/admin/wipe-reset"), eq(Map.of()), eq(BankWipeResetResultDto.class)))
        .thenReturn(null);

    controller.wipeReset("WIPE", attrs);

    assertEquals(Boolean.TRUE, attrs.getFlashAttributes().get("wipeNoop"));
  }
}
