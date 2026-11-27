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

package de.greluc.krt.profit.basetool.frontend.identity.web;

import de.greluc.krt.profit.basetool.frontend.identity.client.IdentityBackendClient;
import de.greluc.krt.profit.basetool.frontend.identity.model.AdminDeletionRequestDto;
import de.greluc.krt.profit.basetool.frontend.identity.model.UserDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.session.PrincipalSessionTerminator;
import de.greluc.krt.profit.basetool.frontend.kernel.web.SectionFallback;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Ends the sessions of an account an admin deletes (REQ-DATA-048): the sign-in name is read before
 * the deletion, while the account still exists, and its sessions are deleted once the backend
 * confirmed it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DeletedMemberSessions {

  private final IdentityBackendClient identityClient;
  private final PrincipalSessionTerminator terminator;

  /**
   * Reads the sign-in name of an account about to be deleted.
   *
   * @param userId the account
   * @return the sign-in name, or {@code null} when the backend could not answer; the sessions then
   *     expire on their own
   */
  public @Nullable String principalOf(@NotNull UUID userId) {
    try {
      UserDto user = identityClient.user(userId);
      return user == null ? null : user.username();
    } catch (BackendServiceException e) {
      SectionFallback.rethrowHandOff(e);
      log.warn(
          "Could not read account {} before its deletion; its sessions expire on their own",
          userId);
      return null;
    }
  }

  /**
   * Reads the sign-in name of the account an erasure request is about.
   *
   * @param requestId the pending request
   * @return the sign-in name, or {@code null} when it could not be read
   */
  public @Nullable String principalOfRequest(@NotNull UUID requestId) {
    try {
      return identityClient.deletionRequests().stream()
          .filter(request -> requestId.equals(request.id()))
          .map(AdminDeletionRequestDto::userId)
          .findFirst()
          .map(this::principalOf)
          .orElse(null);
    } catch (BackendServiceException e) {
      SectionFallback.rethrowHandOff(e);
      log.warn(
          "Could not read erasure request {}; the account's sessions expire on their own",
          requestId);
      return null;
    }
  }

  /**
   * Deletes the stored sessions of a deleted account.
   *
   * @param principalName the sign-in name read before the deletion; {@code null} ends nothing
   */
  public void end(@Nullable String principalName) {
    terminator.endSessionsOf(principalName);
  }
}
