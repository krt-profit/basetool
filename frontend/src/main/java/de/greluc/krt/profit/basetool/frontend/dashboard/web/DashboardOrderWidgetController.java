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

import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardStatus;
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardWidgetType;
import de.greluc.krt.profit.basetool.frontend.joborder.client.JobOrderBackendClient;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.MaterialDemandSummaryDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.OrderListScope;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.refinery.client.RefineryBackendClient;
import de.greluc.krt.profit.basetool.frontend.refinery.model.RefineryOrderListDto;
import de.greluc.krt.profit.basetool.frontend.refinery.web.RefineryProgress;
import java.util.List;
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
 * Serves the bodies of the order and refinery widgets (REQ-UI-034): the orders the member's units
 * process, the orders they requested, the open material demand and the finished refinery jobs. Each
 * reads the backend, which scopes it to what the caller may read; a refusal or failure is answered
 * by the central error mapping and shows that widget's error state.
 */
@Controller
@UsesLayoutModel
@RequestMapping("/dashboard/widgets")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class DashboardOrderWidgetController {

  private static final String FRAGMENTS = "dashboard/fragments/order-widgets :: ";

  /** The refinery list order of the ready segment: the longest finished first. */
  private static final String REFINERY_SORT = "endsAt,asc";

  private final JobOrderBackendClient orderClient;

  private final RefineryBackendClient refineryClient;

  /**
   * Renders the orders one of the member's units processes, by priority.
   *
   * @param model the Thymeleaf model
   * @param unit the pinned org unit, or {@code null} to follow the active context
   * @param rows the rows shown
   * @param status the status filter
   * @return the {@code ordersToProcess} fragment
   */
  @NotNull
  @GetMapping(value = "/orders-to-process", headers = "X-Requested-With=XMLHttpRequest")
  public String ordersToProcess(
      @NotNull Model model,
      @RequestParam(required = false) @Nullable UUID unit,
      @RequestParam(required = false) @Nullable Integer rows,
      @RequestParam(required = false) @Nullable DashboardStatus status) {
    DashboardStatus resolved =
        DashboardWidgetOptions.orderStatus(DashboardWidgetType.ORDERS_TO_PROCESS, status);
    PageResponse<JobOrderDto> page =
        orderClient.ordersToProcess(
            unit,
            DashboardWidgetOptions.rows(rows),
            DashboardWidgetOptions.orderStatuses(resolved));
    addOrders(model, page, "toProcessOrders", "toProcessTotal");
    model.addAttribute("toProcessShowsUnit", unit == null);
    return FRAGMENTS + "ordersToProcess";
  }

  /**
   * Renders the orders the member's units requested, by priority.
   *
   * @param model the Thymeleaf model
   * @param rows the rows shown
   * @param status the status filter
   * @return the {@code myOrders} fragment
   */
  @NotNull
  @GetMapping(value = "/my-orders", headers = "X-Requested-With=XMLHttpRequest")
  public String myOrders(
      @NotNull Model model,
      @RequestParam(required = false) @Nullable Integer rows,
      @RequestParam(required = false) @Nullable DashboardStatus status) {
    DashboardStatus resolved =
        DashboardWidgetOptions.orderStatus(DashboardWidgetType.MY_ORDERS, status);
    PageResponse<JobOrderDto> page =
        orderClient.orders(
            OrderListScope.MINE,
            0,
            DashboardWidgetOptions.rows(rows),
            DashboardWidgetOptions.orderStatuses(resolved),
            List.of());
    addOrders(model, page, "myOrders", "myOrdersTotal");
    return FRAGMENTS + "myOrders";
  }

  /**
   * Renders the open material demand: the buckets with a gap, in the overview's order.
   *
   * @param model the Thymeleaf model
   * @param unit the pinned org unit, or {@code null} to follow the active context
   * @param rows the rows shown
   * @return the {@code materialDemand} fragment
   */
  @NotNull
  @GetMapping(value = "/material-demand", headers = "X-Requested-With=XMLHttpRequest")
  public String materialDemand(
      @NotNull Model model,
      @RequestParam(required = false) @Nullable UUID unit,
      @RequestParam(required = false) @Nullable Integer rows) {
    MaterialDemandSummaryDto summary =
        orderClient.materialDemandSummary(unit, DashboardWidgetOptions.rows(rows));
    model.addAttribute("demandRows", summary == null ? List.of() : summary.rows());
    model.addAttribute("demandTotal", summary == null ? 0L : summary.openBuckets());
    model.addAttribute("demandShowsUnit", unit == null);
    return FRAGMENTS + "materialDemand";
  }

  /**
   * Renders the open refinery orders whose end has passed, the next to be stored first.
   *
   * @param model the Thymeleaf model
   * @param rows the rows shown
   * @return the {@code refineryReady} fragment
   */
  @NotNull
  @GetMapping(value = "/refinery-ready", headers = "X-Requested-With=XMLHttpRequest")
  public String refineryReady(
      @NotNull Model model, @RequestParam(required = false) @Nullable Integer rows) {
    PageResponse<RefineryOrderListDto> page =
        refineryClient.orderPage(
            false,
            RefineryProgress.ACTIVE_STATUSES,
            true,
            null,
            REFINERY_SORT,
            0,
            DashboardWidgetOptions.rows(rows));
    model.addAttribute("readyOrders", page == null ? List.of() : page.content());
    model.addAttribute("readyTotal", page == null ? 0L : page.totalElements());
    return FRAGMENTS + "refineryReady";
  }

  private static void addOrders(
      @NotNull Model model,
      @Nullable PageResponse<JobOrderDto> page,
      @NotNull String ordersAttribute,
      @NotNull String totalAttribute) {
    model.addAttribute(ordersAttribute, page == null ? List.of() : page.content());
    model.addAttribute(totalAttribute, page == null ? 0L : page.totalElements());
  }
}
