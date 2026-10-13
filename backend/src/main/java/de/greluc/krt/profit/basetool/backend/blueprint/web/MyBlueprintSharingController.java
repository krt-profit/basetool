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

package de.greluc.krt.profit.basetool.backend.blueprint.web;

import de.greluc.krt.profit.basetool.backend.blueprint.api.MemberBlueprintSharings;
import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Whether the calling member shares their blueprints globally (REQ-INV-018), the profile page's
 * sharing toggle. The JWT identifies the row, so a caller can only ever read or change their own;
 * the version is the setting's own optimistic-lock counter.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Transactional
public class MyBlueprintSharingController {

  private final UserService userService;
  private final MemberBlueprintSharings memberBlueprintSharings;

  /**
   * Returns whether the calling user has opted into global blueprint sharing, plus the current
   * optimistic-lock version, backing the profile page's blueprint-sharing toggle. Derived from the
   * JWT subject — a caller can only ever read their own.
   *
   * @param jwt caller's JWT; never {@code null} thanks to the {@code @PreAuthorize}.
   * @return the current opt-in flag plus the user-row version.
   */
  @NotNull
  @GetMapping("/blueprints/me/sharing")
  @PreAuthorize("isAuthenticated()")
  @Transactional(readOnly = true)
  public MyBlueprintSharingResponse getMyBlueprintSharing(@AuthenticationPrincipal Jwt jwt) {
    MemberBlueprintSharings.Snapshot sharing =
        memberBlueprintSharings.snapshotOf(userService.getUserIdFromJwt(jwt));
    return new MyBlueprintSharingResponse(sharing.shareGlobally(), sharing.version());
  }

  /**
   * Sets whether the calling user opts into global blueprint sharing (REQ-INV-018), guarded by the
   * optimistic-lock version.
   *
   * <p>When enabled, the user's blueprints count toward every org unit's availability and coverage
   * views (read-only, name-only).
   *
   * @param jwt caller's JWT; never {@code null} thanks to the {@code @PreAuthorize}.
   * @param request the new opt-in value plus the expected version.
   * @return the persisted flag and the new version.
   */
  @NotNull
  @PutMapping("/blueprints/me/sharing")
  @PreAuthorize("isAuthenticated()")
  public MyBlueprintSharingResponse updateMyBlueprintSharing(
      @AuthenticationPrincipal Jwt jwt,
      @NotNull @RequestBody @Valid MyBlueprintSharingRequest request) {
    MemberBlueprintSharings.Snapshot sharing =
        memberBlueprintSharings.update(
            userService.getUserIdFromJwt(jwt),
            request.shareBlueprintsGlobally(),
            request.version());
    return new MyBlueprintSharingResponse(sharing.shareGlobally(), sharing.version());
  }

  /**
   * Response for {@link #getMyBlueprintSharing} / {@link #updateMyBlueprintSharing}: whether the
   * user opted into global blueprint sharing plus the user-row optimistic-lock version the toggle
   * echoes back on save.
   *
   * @param shareBlueprintsGlobally the stored opt-in flag.
   * @param version the user row's current {@code @Version}.
   */
  public record MyBlueprintSharingResponse(boolean shareBlueprintsGlobally, Long version) {}

  /**
   * Body for {@link #updateMyBlueprintSharing}: the new opt-in flag and the expected
   * optimistic-lock version.
   *
   * @param shareBlueprintsGlobally the new opt-in value; never {@code null}.
   * @param version the {@code @Version} of the user row the caller last read; never {@code null}.
   */
  public record MyBlueprintSharingRequest(
      @jakarta.validation.constraints.NotNull Boolean shareBlueprintsGlobally,
      @jakarta.validation.constraints.NotNull Long version) {}
}
