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

package de.greluc.krt.profit.basetool.frontend.dashboard.web;

import de.greluc.krt.profit.basetool.frontend.bank.client.OrgUnitBankAccountsBackendClient;
import de.greluc.krt.profit.basetool.frontend.bank.model.OrgUnitBankVisibleAccountDto;
import de.greluc.krt.profit.basetool.frontend.bank.web.BankSparkline;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardStatus;
import de.greluc.krt.profit.basetool.frontend.inventory.client.StockLevelBackendClient;
import de.greluc.krt.profit.basetool.frontend.inventory.model.StockLevelDto;
import de.greluc.krt.profit.basetool.frontend.joborder.client.JobOrderBackendClient;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.OrderListScope;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Serves the bodies of the work widgets (REQ-UI-034): the orders the member is an assignee of, the
 * low minimum stock levels and the viewable org-unit bank accounts. Each reads the backend, which
 * scopes it to the units the caller holds the grant for; a backend failure is answered by the
 * central error mapping and shows that widget's error state.
 */
@Controller
@UsesLayoutModel
@RequestMapping("/dashboard/widgets")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class DashboardWorkWidgetController {

  /** The rows of the bank widget while the member chose none. */
  private static final int BANK_DEFAULT_ROWS = 3;

  private static final String FRAGMENTS = "dashboard/fragments/work-widgets :: ";

  private final JobOrderBackendClient orderClient;

  private final StockLevelBackendClient stockLevelClient;

  private final OrgUnitBankAccountsBackendClient bankClient;

  /**
   * Renders the orders the member is an assignee of, by priority.
   *
   * @param model the Thymeleaf model
   * @param rows the rows shown
   * @param status the status filter
   * @return the {@code assignedOrders} fragment
   */
  @NotNull
  @GetMapping(value = "/assigned-orders", headers = "X-Requested-With=XMLHttpRequest")
  public String assignedOrders(
      @NotNull Model model,
      @RequestParam(required = false) @Nullable Integer rows,
      @RequestParam(required = false) @Nullable DashboardStatus status) {
    DashboardStatus resolved = DashboardWidgetOptions.orderStatus(status);
    PageResponse<JobOrderDto> page =
        orderClient.orders(
            OrderListScope.ASSIGNED,
            0,
            DashboardWidgetOptions.rows(rows),
            DashboardWidgetOptions.orderStatuses(resolved),
            List.of());
    model.addAttribute("assignedOrders", page == null ? List.of() : page.content());
    model.addAttribute("assignedTotal", page == null ? 0L : page.totalElements());
    return FRAGMENTS + "assignedOrders";
  }

  /**
   * Renders the low minimum stock levels, emptiest first.
   *
   * @param model the Thymeleaf model
   * @param unit the pinned org unit, or {@code null} to follow the active context
   * @param rows the rows shown
   * @return the {@code lowStock} fragment
   */
  @NotNull
  @GetMapping(value = "/low-stock", headers = "X-Requested-With=XMLHttpRequest")
  public String lowStock(
      @NotNull Model model,
      @RequestParam(required = false) @Nullable UUID unit,
      @RequestParam(required = false) @Nullable Integer rows) {
    List<StockLevelDto> low = stockLevelClient.lowLevels(unit);
    List<StockLevelDto> levels = low == null ? List.of() : low;
    model.addAttribute(
        "lowLevels", levels.stream().limit(DashboardWidgetOptions.rows(rows)).toList());
    model.addAttribute("lowTotal", levels.size());
    model.addAttribute("lowShowsUnit", unit == null);
    return FRAGMENTS + "lowStock";
  }

  /**
   * Renders the first org-unit bank accounts the member may view, in the backend's order.
   *
   * @param model the Thymeleaf model
   * @param rows the rows shown
   * @return the {@code bank} fragment
   */
  @NotNull
  @GetMapping(value = "/bank", headers = "X-Requested-With=XMLHttpRequest")
  public String bank(@NotNull Model model, @RequestParam(required = false) @Nullable Integer rows) {
    PageResponse<OrgUnitBankVisibleAccountDto> page =
        bankClient.firstPage(DashboardWidgetOptions.rows(rows, BANK_DEFAULT_ROWS));
    List<OrgUnitBankVisibleAccountDto> accounts = page == null ? List.of() : page.content();
    Map<UUID, BankSparkline.Spark> sparks = new LinkedHashMap<>();
    accounts.forEach(
        account -> sparks.put(account.accountId(), BankSparkline.of(account.sparkline())));
    model.addAttribute("bankAccounts", accounts);
    model.addAttribute("bankSparks", sparks);
    model.addAttribute("bankTotal", page == null ? 0L : page.totalElements());
    return FRAGMENTS + "bank";
  }
}
