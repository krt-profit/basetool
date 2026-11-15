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

package de.greluc.krt.profit.basetool.frontend.patchchange.web;

import de.greluc.krt.profit.basetool.frontend.identity.model.UserDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.ReauthenticationRequiredException;
import de.greluc.krt.profit.basetool.frontend.kernel.web.SectionFallback;
import de.greluc.krt.profit.basetool.frontend.patchchange.client.PatchChangeBackendClient;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.HomeLocationPromptDto;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Reads the backend data of the two home-location pages, the member's own {@code /home-location}
 * and the admin's member tab, and returns it as immutable page records (REQ-PATCH-020,
 * REQ-PATCH-022, ADR-0291).
 *
 * <p>Every part is optional: a part that cannot be read, or that the backend answered without a
 * body, is logged once through {@link SectionFallback} and shows as an empty {@link Optional}. A
 * lost session propagates as {@link ReauthenticationRequiredException}. It reads only; the choice
 * goes through {@link PatchChangeBackendClient}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HomeLocationPageReader {

  private final PatchChangeBackendClient patchChangeClient;

  /**
   * What the admin's member tab renders.
   *
   * @param prompt the member's prompt; empty when it cannot be read
   * @param memberName the name the page head shows for the member, the effective name before the
   *     username; empty when the member was not asked for, cannot be read or has neither
   */
  public record MemberTab(
      @NotNull Optional<HomeLocationPromptDto> prompt, @NotNull Optional<String> memberName) {}

  /**
   * Reads the caller's own prompt.
   *
   * @return the prompt, empty when it cannot be read
   * @throws ReauthenticationRequiredException when the user's session can no longer reach the
   *     backend
   */
  public @NotNull Optional<HomeLocationPromptDto> myPrompt() {
    return SectionFallback.load(
        log,
        "home-location-prompt",
        () -> Optional.ofNullable(patchChangeClient.myHomeLocationPrompt()),
        Optional.empty());
  }

  /**
   * Reads a member's prompt and, for the full tab, the member's name.
   *
   * @param userId the member
   * @param withMemberName whether the page head is rendered and needs the member's name
   * @return the tab
   * @throws ReauthenticationRequiredException when the user's session can no longer reach the
   *     backend
   */
  public @NotNull MemberTab memberTab(@NotNull UUID userId, boolean withMemberName) {
    Optional<HomeLocationPromptDto> prompt =
        SectionFallback.load(
            log,
            "member-home-location",
            () -> Optional.ofNullable(patchChangeClient.memberHomeLocationPrompt(userId)),
            Optional.empty());
    return new MemberTab(prompt, withMemberName ? memberName(userId) : Optional.empty());
  }

  /**
   * Reads the name the page head shows for a member.
   *
   * @param userId the member
   * @return the effective name, else the username; empty when the member cannot be read
   */
  private @NotNull Optional<String> memberName(@NotNull UUID userId) {
    return SectionFallback.load(
        log,
        "member-home-location-member",
        () ->
            Optional.ofNullable(patchChangeClient.member(userId))
                .map(HomeLocationPageReader::displayName),
        Optional.empty());
  }

  /**
   * Picks the name a member is shown by.
   *
   * @param member the member
   * @return the effective name, else the username, or {@code null} when it has neither
   */
  private static @Nullable String displayName(@NotNull UserDto member) {
    return member.effectiveName() != null ? member.effectiveName() : member.username();
  }
}
