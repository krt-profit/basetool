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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.model.dto.BankBalanceSeriesDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankWipeResetResultDto;
import de.greluc.krt.profit.basetool.backend.service.BankAccountService;
import de.greluc.krt.profit.basetool.backend.service.BankAuditReportService;
import de.greluc.krt.profit.basetool.backend.service.BankAuditService;
import de.greluc.krt.profit.basetool.backend.service.BankDashboardService;
import de.greluc.krt.profit.basetool.backend.service.BankGrantService;
import de.greluc.krt.profit.basetool.backend.service.BankHolderService;
import de.greluc.krt.profit.basetool.backend.service.BankLedgerService;
import de.greluc.krt.profit.basetool.backend.service.BankManagementReportService;
import de.greluc.krt.profit.basetool.backend.service.BankSecurityService;
import de.greluc.krt.profit.basetool.backend.service.BankStatementReportService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MockMvc gate matrix for the bank surface (REQ-BANK-010): the role checks of the URL matrix and
 * the method-level {@code @PreAuthorize} annotations — incl. the two carve-outs that matter most:
 * members see <em>nothing</em>, and bank management does NOT pass the admin-only {@code
 * /api/v1/bank/admin/**} URL gate. Capability gates delegate to the (mocked) {@code
 * BankSecurityService}; its real decision logic is covered by {@code BankSecurityServiceTest}.
 */
@SpringBootTest
@ActiveProfiles("test")
class BankControllerSecurityTest {

  @Autowired private WebApplicationContext context;
  private MockMvc mockMvc;

  @MockitoBean private BankAccountService bankAccountService;
  @MockitoBean private BankLedgerService bankLedgerService;
  @MockitoBean private BankHolderService bankHolderService;
  @MockitoBean private BankGrantService bankGrantService;
  @MockitoBean private BankDashboardService bankDashboardService;
  @MockitoBean private BankAuditService bankAuditService;
  @MockitoBean private BankAuditReportService bankAuditReportService;
  @MockitoBean private BankSecurityService bankSecurityService;
  @MockitoBean private BankStatementReportService bankStatementReportService;
  @MockitoBean private BankManagementReportService bankManagementReportService;
  @MockitoBean private JwtDecoder jwtDecoder;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void accountsList_member_isForbidden() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/bank/accounts")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void accountsList_bankEmployee_isAllowed() throws Exception {
    when(bankAccountService.getAccounts(org.mockito.ArgumentMatchers.anyBoolean(), any(), any()))
        .thenReturn(org.springframework.data.domain.Page.empty());
    mockMvc
        .perform(
            get("/api/v1/bank/accounts")
                .with(
                    jwt()
                        .jwt(j -> j.subject(UUID.randomUUID().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_BANK_EMPLOYEE"))))
        .andExpect(status().isOk());
  }

  @Test
  void accountsList_admin_passesViaHierarchy() throws Exception {
    when(bankAccountService.getAccounts(org.mockito.ArgumentMatchers.anyBoolean(), any(), any()))
        .thenReturn(org.springframework.data.domain.Page.empty());
    mockMvc
        .perform(
            get("/api/v1/bank/accounts")
                .with(
                    jwt()
                        .jwt(j -> j.subject(UUID.randomUUID().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk());
  }

  @Test
  void bankGates_ignoreTheActiveOrgUnitPinHeader() throws Exception {
    // REQ-BANK-008: the X-Active-Org-Unit-Id admin pin influences no bank gate, in either
    // direction — it neither grants a member access nor alters a bank employee's.
    String pinnedOrgUnit = UUID.randomUUID().toString();

    // A member with the pin set still sees no bank surface.
    mockMvc
        .perform(
            get("/api/v1/bank/accounts")
                .header("X-Active-Org-Unit-Id", pinnedOrgUnit)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"))))
        .andExpect(status().isForbidden());

    // A bank employee with the same pin set still passes (no org-unit scoping is applied).
    when(bankAccountService.getAccounts(org.mockito.ArgumentMatchers.anyBoolean(), any(), any()))
        .thenReturn(org.springframework.data.domain.Page.empty());
    mockMvc
        .perform(
            get("/api/v1/bank/accounts")
                .header("X-Active-Org-Unit-Id", pinnedOrgUnit)
                .with(
                    jwt()
                        .jwt(j -> j.subject(UUID.randomUUID().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_BANK_EMPLOYEE"))))
        .andExpect(status().isOk());
  }

  @Test
  void accountCreate_member_isForbidden() throws Exception {
    // A user without any bank role cannot reach the create endpoint (URL/method gate).
    mockMvc
        .perform(
            post("/api/v1/bank/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"X\",\"type\":\"SPECIAL\"}")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void accountCreate_employee_reachesEndpoint() throws Exception {
    // REQ-BANK-030: the create gate is now BANK_EMPLOYEE (an employee may create SPECIAL accounts);
    // the SPECIAL-only restriction is enforced in the service (see BankAccountServiceTest). The
    // service is mocked here, so reaching it yields 201.
    mockMvc
        .perform(
            post("/api/v1/bank/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"X\",\"type\":\"SPECIAL\"}")
                .with(
                    jwt()
                        .jwt(j -> j.subject(UUID.randomUUID().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_BANK_EMPLOYEE"))))
        .andExpect(status().isCreated());
  }

  @Test
  void accountCreate_management_isAllowed() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/bank/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"X\",\"type\":\"SPECIAL\"}")
                .with(
                    jwt()
                        .jwt(j -> j.subject(UUID.randomUUID().toString()))
                        .authorities(new SimpleGrantedAuthority("ROLE_BANK_MANAGEMENT"))))
        .andExpect(status().isCreated());
  }

  @Test
  void balanceSeries_withoutAccess_isForbidden() throws Exception {
    // REQ-BANK-049: the balance-series read is gated exactly like the booking history (canSee).
    when(bankSecurityService.canSee(any(UUID.class), any())).thenReturn(false);
    mockMvc
        .perform(
            get("/api/v1/bank/accounts/{id}/balance-series", UUID.randomUUID())
                .param("from", "2026-06-01T00:00:00Z")
                .param("to", "2026-07-01T00:00:00Z")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BANK_EMPLOYEE"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void balanceSeries_withAccess_isAllowed() throws Exception {
    when(bankSecurityService.canSee(any(UUID.class), any())).thenReturn(true);
    when(bankAccountService.getBalanceSeries(any(UUID.class), any(), any()))
        .thenReturn(new BankBalanceSeriesDto(List.of(), null));
    mockMvc
        .perform(
            get("/api/v1/bank/accounts/{id}/balance-series", UUID.randomUUID())
                .param("from", "2026-06-01T00:00:00Z")
                .param("to", "2026-07-01T00:00:00Z")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BANK_EMPLOYEE"))))
        .andExpect(status().isOk());
  }

  @Test
  void deposit_withoutCapability_isForbidden() throws Exception {
    when(bankSecurityService.canDeposit(any(UUID.class), any())).thenReturn(false);
    mockMvc
        .perform(
            post("/api/v1/bank/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(depositBody())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BANK_EMPLOYEE"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void deposit_withCapability_isAllowed() throws Exception {
    when(bankSecurityService.canDeposit(any(UUID.class), any())).thenReturn(true);
    mockMvc
        .perform(
            post("/api/v1/bank/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(depositBody())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BANK_EMPLOYEE"))))
        .andExpect(status().isCreated());
  }

  @Test
  void deposit_fractionalAmount_isRejectedWith400() throws Exception {
    when(bankSecurityService.canDeposit(any(UUID.class), any())).thenReturn(true);
    String body =
        "{\"accountId\":\""
            + UUID.randomUUID()
            + "\",\"holderId\":\""
            + UUID.randomUUID()
            + "\",\"amount\":500.5}";
    mockMvc
        .perform(
            post("/api/v1/bank/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BANK_EMPLOYEE"))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void reversal_employee_isForbidden() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/bank/transactions/{id}/reversal", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BANK_EMPLOYEE"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void grants_employee_isForbidden_management_isAllowed() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/bank/grants")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BANK_EMPLOYEE"))))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            get("/api/v1/bank/grants")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BANK_MANAGEMENT"))))
        .andExpect(status().isOk());
  }

  @Test
  void adminSurface_management_isForbidden_admin_isAllowed() throws Exception {
    // The audit log is admin-only — bank management must NOT see it (REQ-BANK-010/-012).
    mockMvc
        .perform(
            get("/api/v1/bank/admin/audit")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BANK_MANAGEMENT"))))
        .andExpect(status().isForbidden());
    when(bankAuditService.getEvents(any(), any(), any(), any(), any(), any()))
        .thenReturn(org.springframework.data.domain.Page.empty());
    mockMvc
        .perform(
            get("/api/v1/bank/admin/audit")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk());
  }

  @Test
  void wipeReset_management_isForbidden_admin_isAllowed() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/bank/admin/wipe-reset")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BANK_MANAGEMENT"))))
        .andExpect(status().isForbidden());
    when(bankLedgerService.resetAllBalances())
        .thenReturn(new BankWipeResetResultDto(0, 0, BigDecimal.ZERO));
    mockMvc
        .perform(
            post("/api/v1/bank/admin/wipe-reset")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk());
  }

  @Test
  void bankAuditPurge_management_isForbidden_admin_isAllowed() throws Exception {
    // The retention purge is admin-only (REQ-AUDIT-004) — bank management must NOT pass the
    // admin-only /api/v1/bank/admin/** gate.
    mockMvc
        .perform(
            delete("/api/v1/bank/admin/audit")
                .param("before", "2026-01-01T00:00:00Z")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BANK_MANAGEMENT"))))
        .andExpect(status().isForbidden());
    when(bankAuditService.purgeBefore(any())).thenReturn(0);
    mockMvc
        .perform(
            delete("/api/v1/bank/admin/audit")
                .param("before", "2026-01-01T00:00:00Z")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk());
  }

  @Test
  void bankAuditExportJson_management_isForbidden_admin_isAllowed() throws Exception {
    // The JSON export is admin-only (REQ-AUDIT-003) — bank management must NOT pass.
    mockMvc
        .perform(
            get("/api/v1/bank/admin/audit/export.json")
                .param("from", "2026-01-01T00:00:00Z")
                .param("to", "2026-02-01T00:00:00Z")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BANK_MANAGEMENT"))))
        .andExpect(status().isForbidden());
    when(bankAuditReportService.generateAuditLogJson(any(), any())).thenReturn(java.util.List.of());
    mockMvc
        .perform(
            get("/api/v1/bank/admin/audit/export.json")
                .param("from", "2026-01-01T00:00:00Z")
                .param("to", "2026-02-01T00:00:00Z")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk());
  }

  @Test
  void dashboard_guestWithoutRoles_isForbidden() throws Exception {
    mockMvc.perform(get("/api/v1/bank/dashboard").with(jwt())).andExpect(status().isForbidden());
  }

  @Test
  void statement_withoutVisibility_isForbidden() throws Exception {
    when(bankSecurityService.canSee(any(UUID.class), any())).thenReturn(false);
    mockMvc
        .perform(
            get("/api/v1/bank/accounts/{id}/statement", UUID.randomUUID())
                .param("from", "2026-01-01T00:00:00Z")
                .param("to", "2026-02-01T00:00:00Z")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BANK_EMPLOYEE"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void statement_withVisibility_isAllowed() throws Exception {
    when(bankSecurityService.canSee(any(UUID.class), any())).thenReturn(true);
    when(bankStatementReportService.generateStatement(any(), any(), any(), any()))
        .thenReturn(new byte[] {1, 2, 3});
    mockMvc
        .perform(
            get("/api/v1/bank/accounts/{id}/statement", UUID.randomUUID())
                .param("from", "2026-01-01T00:00:00Z")
                .param("to", "2026-02-01T00:00:00Z")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BANK_EMPLOYEE"))))
        .andExpect(status().isOk());
  }

  @Test
  void holderHistory_withoutVisibility_isForbidden() throws Exception {
    // REQ-BANK-032: a bank employee may read only their own holder's history; the canSeeHolder
    // gate denies someone else's holder, even though the URL role gate (BANK_EMPLOYEE) passes.
    when(bankSecurityService.canSeeHolder(any(UUID.class), any())).thenReturn(false);
    mockMvc
        .perform(
            get("/api/v1/bank/holders/{id}/transactions", UUID.randomUUID())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BANK_EMPLOYEE"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void holderHistory_withVisibility_isAllowed() throws Exception {
    when(bankSecurityService.canSeeHolder(any(UUID.class), any())).thenReturn(true);
    when(bankHolderService.getHolderBookings(any(), any()))
        .thenReturn(org.springframework.data.domain.Page.empty());
    mockMvc
        .perform(
            get("/api/v1/bank/holders/{id}/transactions", UUID.randomUUID())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BANK_EMPLOYEE"))))
        .andExpect(status().isOk());
  }

  @Test
  void threeMonthReport_employee_isForbidden_management_isAllowed() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/bank/export/three-month-report")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BANK_EMPLOYEE"))))
        .andExpect(status().isForbidden());
    when(bankManagementReportService.generateThreeMonthReport(any()))
        .thenReturn(new byte[] {1, 2, 3});
    mockMvc
        .perform(
            get("/api/v1/bank/export/three-month-report")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BANK_MANAGEMENT"))))
        .andExpect(status().isOk());
  }

  /** A syntactically valid deposit body with random ids. */
  private static String depositBody() {
    return "{\"accountId\":\""
        + UUID.randomUUID()
        + "\",\"holderId\":\""
        + UUID.randomUUID()
        + "\",\"amount\":100,\"note\":\"x\"}";
  }
}
