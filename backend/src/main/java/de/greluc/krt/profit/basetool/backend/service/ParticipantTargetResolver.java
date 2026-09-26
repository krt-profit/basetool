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

package de.greluc.krt.profit.basetool.backend.service;

import de.greluc.krt.profit.basetool.backend.exception.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.model.User;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Resolves the person a mission roster row is about from an explicit {@code userId} or a free-text
 * name.
 *
 * <p>A free-text name is matched case-insensitively against every member's {@code username} and
 * {@code displayName} ({@link UserService#findMatchesByExactName}):
 *
 * <ul>
 *   <li>exactly one match: the target is that member;
 *   <li>no match: the target is the name, recorded as an external person;
 *   <li>more than one match: {@link BusinessConflictException} (409).
 * </ul>
 *
 * <p>Whether the caller may name the resolved person is decided by the controller, not here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParticipantTargetResolver {

  /** Runs the case-insensitive exact-name lookup against the member table. */
  private final UserService userService;

  /**
   * The person a roster row is about: a registered member, an external name, or, when both are
   * {@code null}, nobody.
   *
   * @param userId the registered member, or {@code null}
   * @param guestName the external person's name, or {@code null}; kept as submitted, untrimmed
   */
  public record ParticipantTarget(@Nullable UUID userId, @Nullable String guestName) {}

  /**
   * Resolves a submission to its target. An explicit {@code userId} wins and is returned with the
   * submitted name unchanged; only a submission with no id and a non-blank name is looked up.
   *
   * @param userId the member picked from the autocomplete, or {@code null}
   * @param name the free-text name, or {@code null}
   * @param ambiguityMessage the conflict message for more than one match, naming the ambiguous
   *     field
   * @return the resolved target; never {@code null}
   * @throws BusinessConflictException when the name matches more than one member
   */
  @NotNull
  public ParticipantTarget resolve(
      @Nullable UUID userId, @Nullable String name, @NotNull String ambiguityMessage) {
    if (userId != null || name == null || name.isBlank()) {
      return new ParticipantTarget(userId, name);
    }
    List<User> matches = userService.findMatchesByExactName(name);
    if (matches.size() > 1) {
      log.debug("Free-text roster name is ambiguous ({} matches)", matches.size());
      throw new BusinessConflictException(ambiguityMessage);
    }
    if (matches.size() == 1) {
      UUID resolved = matches.getFirst().getId();
      log.debug("Resolved free-text roster name to userId {}", resolved);
      return new ParticipantTarget(resolved, null);
    }
    return new ParticipantTarget(null, name);
  }
}
