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

package de.greluc.krt.profit.basetool.backend.mission.web;

import de.greluc.krt.profit.basetool.backend.identity.api.PayoutPreference;
import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.mission.api.MemberPayoutSettings;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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
 * The calling member's default payout preference, the profile page's payout selector. The JWT
 * identifies the row, so a caller can only ever read or change their own; the version is the
 * setting's own optimistic-lock counter.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Transactional
public class MyPayoutPreferenceController {

  private final UserService userService;
  private final MemberPayoutSettings memberPayoutSettings;

  /**
   * Returns the calling user's personal default payout preference and the current optimistic-lock
   * version, backing the profile page's payout-preference selector. Derived from the JWT subject —
   * a caller can only ever read their own. A {@code null} preference means the user has made no
   * explicit choice yet (mission sign-up then falls back to {@code PAYOUT}, and the selector
   * pre-selects {@code PAYOUT}).
   *
   * @param jwt caller's JWT; never {@code null} thanks to the {@code @PreAuthorize}.
   * @return the current default payout preference (possibly {@code null}) plus the user-row
   *     version.
   */
  @NotNull
  @GetMapping("/missions/me/payout-preference")
  @PreAuthorize("isAuthenticated()")
  @Transactional(readOnly = true)
  public MyPayoutPreferenceResponse getMyPayoutPreference(@AuthenticationPrincipal Jwt jwt) {
    MemberPayoutSettings.Snapshot setting =
        memberPayoutSettings.snapshotOf(userService.getUserIdFromJwt(jwt));
    return new MyPayoutPreferenceResponse(setting.preference(), setting.version());
  }

  /**
   * Sets the calling user's personal default payout preference. The JWT identifies the row — no
   * impersonation possible. Carries the optimistic-lock version so a concurrent edit surfaces as a
   * 409 instead of a silent overwrite. The new value only pre-fills future mission sign-ups; it
   * does not rewrite existing participations.
   *
   * @param jwt caller's JWT; never {@code null} thanks to the {@code @PreAuthorize}.
   * @param request the new preference plus the expected version.
   * @return the persisted preference and the new version.
   */
  @NotNull
  @PutMapping("/missions/me/payout-preference")
  @PreAuthorize("isAuthenticated()")
  public MyPayoutPreferenceResponse updateMyPayoutPreference(
      @AuthenticationPrincipal Jwt jwt,
      @NotNull @RequestBody @Valid MyPayoutPreferenceRequest request) {
    MemberPayoutSettings.Snapshot setting =
        memberPayoutSettings.update(
            userService.getUserIdFromJwt(jwt), request.preference(), request.version());
    return new MyPayoutPreferenceResponse(setting.preference(), setting.version());
  }

  /**
   * Response for {@link #getMyPayoutPreference} / {@link #updateMyPayoutPreference}: the user's
   * current default payout preference (or {@code null} when never chosen) plus the user-row
   * optimistic-lock version the selector echoes back on save.
   *
   * @param defaultPayoutPreference the stored default, or {@code null} for "no explicit choice".
   * @param version the user row's current {@code @Version}.
   */
  public record MyPayoutPreferenceResponse(
      @Nullable PayoutPreference defaultPayoutPreference, Long version) {}

  /**
   * Body for {@link #updateMyPayoutPreference}: the new default payout preference and the expected
   * optimistic-lock version. The preference is {@code @NotNull} — the profile selector always posts
   * a concrete {@code PAYOUT} / {@code DONATE} (a user wanting the implicit default simply picks
   * {@code PAYOUT}); there is no API path that clears it back to {@code null}.
   *
   * @param preference the new default payout preference; never {@code null}.
   * @param version the {@code @Version} of the user row the caller last read; never {@code null}.
   */
  public record MyPayoutPreferenceRequest(
      @jakarta.validation.constraints.NotNull PayoutPreference preference,
      @jakarta.validation.constraints.NotNull Long version) {}
}
