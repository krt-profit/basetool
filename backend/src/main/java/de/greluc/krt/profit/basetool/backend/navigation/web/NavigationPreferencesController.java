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

import de.greluc.krt.profit.basetool.backend.navigation.internal.AppearanceResponse;
import de.greluc.krt.profit.basetool.backend.navigation.internal.AppearanceUpdateRequest;
import de.greluc.krt.profit.basetool.backend.navigation.internal.FavouritesResponse;
import de.greluc.krt.profit.basetool.backend.navigation.internal.FavouritesUpdateRequest;
import de.greluc.krt.profit.basetool.backend.navigation.internal.NavigationMetrics;
import de.greluc.krt.profit.basetool.backend.navigation.internal.NavigationPreferencesResponse;
import de.greluc.krt.profit.basetool.backend.navigation.internal.NavigationPreferencesService;
import de.greluc.krt.profit.basetool.backend.navigation.internal.NavigationSection;
import de.greluc.krt.profit.basetool.backend.navigation.internal.NavigationWriteOutcome;
import de.greluc.krt.profit.basetool.backend.navigation.internal.PruneRequest;
import de.greluc.krt.profit.basetool.backend.platform.api.CurrentUserId;
import de.greluc.krt.profit.basetool.backend.platform.api.GlobalExceptionHandler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints of the caller's own navigation preferences (REQ-UI-032). The owner is always the
 * JWT subject; no endpoint takes a member id, and there is no admin view.
 */
@RestController
@RequestMapping("/api/v1/navigation/preferences")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Tag(name = "Navigation", description = "The caller's own navigation preferences.")
@SecurityRequirement(name = "bearer-jwt")
public class NavigationPreferencesController {

  private final NavigationPreferencesService service;
  private final NavigationMetrics metrics;
  private final GlobalExceptionHandler problems;

  /**
   * Reads every preference section of the caller.
   *
   * @param userId the caller's {@code app_user.id}
   * @return the sections with their versions
   */
  @GetMapping
  @Operation(summary = "Read the caller's navigation preferences.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "The caller's sections."),
    @ApiResponse(responseCode = "401", description = "Authentication required.")
  })
  public NavigationPreferencesResponse read(@CurrentUserId UUID userId) {
    return service.read(userId);
  }

  /**
   * Replaces the caller's favourites.
   *
   * @param request the new keys and the version the caller last saw
   * @param userId the caller's {@code app_user.id}
   * @return the stored favourites with the new version
   */
  @PutMapping("/favourites")
  @Operation(summary = "Replace the caller's favourites (at most 12).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Favourites stored."),
    @ApiResponse(responseCode = "400", description = "Validation failed or a key repeats."),
    @ApiResponse(responseCode = "409", description = "The favourites version is stale.")
  })
  public FavouritesResponse replaceFavourites(
      @Valid @RequestBody FavouritesUpdateRequest request, @CurrentUserId UUID userId) {
    return service.replaceFavourites(userId, request);
  }

  /**
   * Replaces the caller's "Darstellung" section.
   *
   * @param request the new desktop mode and the version the caller last saw
   * @param userId the caller's {@code app_user.id}
   * @return the stored appearance with the new version
   */
  @PutMapping("/appearance")
  @Operation(summary = "Replace the caller's appearance section (desktop mode).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Appearance stored."),
    @ApiResponse(responseCode = "400", description = "Validation failed."),
    @ApiResponse(responseCode = "409", description = "The appearance version is stale.")
  })
  public AppearanceResponse replaceAppearance(
      @Valid @RequestBody AppearanceUpdateRequest request, @CurrentUserId UUID userId) {
    return service.replaceAppearance(userId, request);
  }

  /**
   * Counts a favourites write that bean validation refused before the service saw it as a rejected
   * write (REQ-OBS-011), then answers it like every other validation failure.
   *
   * @param ex the validation failure
   * @param request the current request
   * @return the {@code 400} problem response
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ProblemDetail> rejected(
      MethodArgumentNotValidException ex, HttpServletRequest request) {
    if (ex.getBindingResult().getTarget() instanceof FavouritesUpdateRequest) {
      metrics.recordWrite(NavigationSection.FAVOURITES, NavigationWriteOutcome.REJECTED);
    } else if (ex.getBindingResult().getTarget() instanceof AppearanceUpdateRequest) {
      metrics.recordWrite(NavigationSection.APPEARANCE, NavigationWriteOutcome.REJECTED);
    }
    return problems.handleValidationExceptions(ex, request);
  }

  /**
   * Deletes entry keys the caller may no longer see from every section.
   *
   * @param request the keys to delete
   * @param userId the caller's {@code app_user.id}
   * @return the caller's sections after the deletion
   */
  @PostMapping("/prune")
  @Operation(summary = "Delete entries the caller may no longer see from every section.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Entries deleted."),
    @ApiResponse(responseCode = "400", description = "Validation failed.")
  })
  public NavigationPreferencesResponse prune(
      @Valid @RequestBody PruneRequest request, @CurrentUserId UUID userId) {
    return service.prune(userId, request.keys());
  }
}
