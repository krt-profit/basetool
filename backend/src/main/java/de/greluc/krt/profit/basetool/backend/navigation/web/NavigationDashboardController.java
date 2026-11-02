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

package de.greluc.krt.profit.basetool.backend.navigation.web;

import de.greluc.krt.profit.basetool.backend.navigation.internal.DashboardLayoutRequest;
import de.greluc.krt.profit.basetool.backend.navigation.internal.DashboardMode;
import de.greluc.krt.profit.basetool.backend.navigation.internal.DashboardOptionsRequest;
import de.greluc.krt.profit.basetool.backend.navigation.internal.DashboardPruneRequest;
import de.greluc.krt.profit.basetool.backend.navigation.internal.DashboardResponse;
import de.greluc.krt.profit.basetool.backend.navigation.internal.DashboardService;
import de.greluc.krt.profit.basetool.backend.navigation.internal.DashboardWidgetResponse;
import de.greluc.krt.profit.basetool.backend.navigation.internal.NavigationMetrics;
import de.greluc.krt.profit.basetool.backend.navigation.internal.NavigationSection;
import de.greluc.krt.profit.basetool.backend.navigation.internal.NavigationWriteOutcome;
import de.greluc.krt.profit.basetool.backend.platform.api.CurrentUserId;
import de.greluc.krt.profit.basetool.backend.platform.api.GlobalExceptionHandler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.HashSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints of the caller's own dashboards (REQ-UI-034). The owner is always the JWT subject;
 * no endpoint takes a member id, and there is no admin view. The admin mode's dashboard is for
 * administrators only.
 */
@RestController
@RequestMapping("/api/v1/navigation/dashboards/{mode}")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated() and (#mode.name() != 'ADMIN' or hasRole('ADMIN'))")
@Tag(name = "Navigation", description = "The caller's own navigation preferences.")
@SecurityRequirement(name = "bearer-jwt")
public class NavigationDashboardController {

  private final DashboardService service;
  private final NavigationMetrics metrics;
  private final GlobalExceptionHandler problems;

  /**
   * Reads a mode's dashboard.
   *
   * @param mode the mode
   * @param userId the caller's {@code app_user.id}
   * @return the dashboard; the mode's default while the caller never changed it
   */
  @GetMapping
  @Operation(operationId = "readDashboard", summary = "Read the caller's dashboard of a mode.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "The dashboard."),
    @ApiResponse(responseCode = "403", description = "The admin dashboard is for administrators.")
  })
  public DashboardResponse read(@PathVariable DashboardMode mode, @CurrentUserId UUID userId) {
    return service.read(userId, mode);
  }

  /**
   * Replaces a mode's layout.
   *
   * @param mode the mode
   * @param request the instances in display order and the layout version the caller last saw
   * @param userId the caller's {@code app_user.id}
   * @return the stored dashboard with the new version
   */
  @PutMapping("/layout")
  @Operation(
      operationId = "replaceDashboardLayout",
      summary = "Replace the caller's dashboard layout of a mode (at most 12 instances).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Layout stored."),
    @ApiResponse(responseCode = "400", description = "Validation failed or a rule is broken."),
    @ApiResponse(responseCode = "409", description = "The layout version is stale.")
  })
  public DashboardResponse replaceLayout(
      @PathVariable DashboardMode mode,
      @Valid @RequestBody DashboardLayoutRequest request,
      @CurrentUserId UUID userId) {
    return service.replaceLayout(userId, mode, request);
  }

  /**
   * Replaces the options of one instance.
   *
   * @param mode the mode
   * @param widgetId the instance
   * @param request the new options and the option version the caller last saw
   * @param userId the caller's {@code app_user.id}
   * @return the stored instance with the new option version
   */
  @PutMapping("/widgets/{widgetId}/options")
  @Operation(
      operationId = "replaceDashboardOptions",
      summary = "Replace the options of one dashboard instance.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Options stored."),
    @ApiResponse(responseCode = "400", description = "The options do not fit the widget."),
    @ApiResponse(responseCode = "404", description = "The caller's dashboard holds no such one."),
    @ApiResponse(responseCode = "409", description = "The option version is stale.")
  })
  public DashboardWidgetResponse replaceOptions(
      @PathVariable DashboardMode mode,
      @PathVariable UUID widgetId,
      @Valid @RequestBody DashboardOptionsRequest request,
      @CurrentUserId UUID userId) {
    return service.replaceOptions(userId, mode, widgetId, request);
  }

  /**
   * Restores a mode's default.
   *
   * @param mode the mode
   * @param version the layout version the caller last saw
   * @param userId the caller's {@code app_user.id}
   * @return the default dashboard with the new version
   */
  @DeleteMapping
  @Operation(
      operationId = "resetDashboard",
      summary = "Restore the caller's default dashboard of a mode.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Default restored."),
    @ApiResponse(responseCode = "409", description = "The layout version is stale.")
  })
  public DashboardResponse reset(
      @PathVariable DashboardMode mode, @RequestParam long version, @CurrentUserId UUID userId) {
    return service.reset(userId, mode, version);
  }

  /**
   * Deletes the instances of widget types the caller may no longer use.
   *
   * @param mode the mode
   * @param request the widget types
   * @param userId the caller's {@code app_user.id}
   * @return the dashboard after the deletion
   */
  @PostMapping("/prune")
  @Operation(
      operationId = "pruneDashboard",
      summary = "Delete instances of widgets the caller may no longer use.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Instances deleted."),
    @ApiResponse(responseCode = "400", description = "Validation failed.")
  })
  public DashboardResponse prune(
      @PathVariable DashboardMode mode,
      @Valid @RequestBody DashboardPruneRequest request,
      @CurrentUserId UUID userId) {
    return service.prune(userId, mode, new HashSet<>(request.types()));
  }

  /**
   * Counts a write that bean validation refused before the service saw it as a rejected write
   * (REQ-OBS-011), then answers it like every other validation failure.
   *
   * @param ex the validation failure
   * @param request the current request
   * @return the {@code 400} problem response
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ProblemDetail> rejected(
      MethodArgumentNotValidException ex, HttpServletRequest request) {
    Object target = ex.getBindingResult().getTarget();
    if (target instanceof DashboardLayoutRequest) {
      metrics.recordWrite(NavigationSection.DASHBOARD, NavigationWriteOutcome.REJECTED);
    } else if (target instanceof DashboardOptionsRequest) {
      metrics.recordWrite(NavigationSection.DASHBOARD, NavigationWriteOutcome.REJECTED);
    }
    return problems.handleValidationExceptions(ex, request);
  }
}
