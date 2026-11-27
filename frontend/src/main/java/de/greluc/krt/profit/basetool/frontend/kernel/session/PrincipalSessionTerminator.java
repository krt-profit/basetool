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

package de.greluc.krt.profit.basetool.frontend.kernel.session;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

/**
 * Ends every stored session of one principal, through the session store's principal-name index
 * ({@code preferred_username}); without an indexed store (the {@code test} profile) there is
 * nothing to end.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PrincipalSessionTerminator {

  private final ObjectProvider<FindByIndexNameSessionRepository<? extends Session>>
      sessionRepository;

  /**
   * Deletes every session the store indexes under {@code principalName}.
   *
   * @param principalName the sign-in name; {@code null} or blank ends nothing
   * @return the number of sessions deleted
   */
  public int endSessionsOf(@Nullable String principalName) {
    if (principalName == null || principalName.isBlank()) {
      return 0;
    }
    FindByIndexNameSessionRepository<? extends Session> repository =
        sessionRepository.getIfAvailable();
    if (repository == null) {
      return 0;
    }
    Set<String> sessionIds = Set.copyOf(repository.findByPrincipalName(principalName).keySet());
    sessionIds.forEach(repository::deleteById);
    log.info("Ended {} session(s) of a deleted account", sessionIds.size());
    return sessionIds.size();
  }
}
