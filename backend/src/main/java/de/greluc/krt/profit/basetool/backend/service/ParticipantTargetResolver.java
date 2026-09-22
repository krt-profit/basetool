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
 * Turns what a person typed into the "who" of a mission roster row: a registered member, or an
 * external name with no account behind it.
 *
 * <p>Both the participant add and the party-lead assignment accept either an explicit {@code
 * userId} — somebody picked from the autocomplete — or a free-text name. A free-text name is looked
 * up case-insensitively against every member's {@code username} and {@code displayName} ({@link
 * UserService#findMatchesByExactName}), so a member who types their own callsign instead of picking
 * it is linked rather than recorded a second time as a stranger of the same name:
 *
 * <ul>
 *   <li>exactly one match → the target is that member, and the name is dropped;
 *   <li>no match → the target is the name, recorded as an external person;
 *   <li>more than one match → {@link BusinessConflictException}, a 409: guessing would put somebody
 *       on the roster who never signed up.
 * </ul>
 *
 * <p>The rule used to be written out four times — twice for the participant add (the plain and the
 * slim endpoint), once for the party lead, and once more inside {@code MissionParticipantService}
 * with a different repository query that threw a 500 instead of the 409 on an ambiguous name. The
 * copies agreed only by care (BE-SIMP-04).
 *
 * <p><b>What this class deliberately does not decide</b> is whether the caller may name the person
 * it resolved. That is the self-vs-manager rule of the participant add, and it stays in the
 * controller, next to the {@code canManageMission} evaluation it depends on: a resolved name is
 * somebody else exactly as a submitted id is.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParticipantTargetResolver {

  /** Runs the case-insensitive exact-name lookup against the member table. */
  private final UserService userService;

  /**
   * The person a roster row is about: a registered member ({@code userId}), an external name
   * ({@code guestName}), or — when both are {@code null} — nobody, which the party lead reads as
   * "clear it" and the participant add refuses.
   *
   * @param userId the registered member, or {@code null}
   * @param guestName the external person's name, or {@code null}; kept as submitted, untrimmed
   */
  public record ParticipantTarget(@Nullable UUID userId, @Nullable String guestName) {}

  /**
   * Resolves a submission to its target.
   *
   * <p>An explicit {@code userId} wins and is returned untouched together with whatever name came
   * with it — the name is then the service's to ignore, as it always was. Only a submission with no
   * id and a non-blank name is looked up.
   *
   * @param userId the member picked from the autocomplete, or {@code null}
   * @param name the free-text name, or {@code null}
   * @param ambiguityMessage the conflict message for more than one match, which names the field
   *     that was ambiguous ("Participant name …", "Party lead name …")
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
