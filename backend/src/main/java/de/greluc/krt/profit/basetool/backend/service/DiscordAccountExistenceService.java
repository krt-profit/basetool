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

import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.StringNormalization;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only existence check backing the Discord first-broker-login collision gate (REQ-SEC-022).
 *
 * <p>Answers a single question for the Keycloak SPI: <em>does a Basetool account already exist that
 * matches an incoming Discord identity?</em> The two name candidates (Discord username + per-guild
 * server nickname) are matched case-insensitively against existing accounts' login {@code username}
 * and in-app {@code displayName}; the Discord e-mail is matched against existing accounts' e-mail.
 * Only the boolean fact is returned — the SPI uses it to reject the registration and point the user
 * at linking their existing account, never to link or inherit anything.
 *
 * <p>No PII ever leaves this service or reaches a log line: the candidate names/e-mail are never
 * logged, and the repository returns a count predicate rather than a row.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DiscordAccountExistenceService {

  private final UserRepository userRepository;

  /**
   * Decides whether an existing account collides with the supplied Discord identity.
   *
   * <p>Inputs are trimmed and lower-cased before matching; blank/{@code null} candidates are
   * ignored. The two name candidates are unioned into a set and matched against {@code username}
   * and {@code displayName} in one query; the e-mail is matched separately. The name query runs
   * only for a non-empty candidate set (so the JPQL {@code IN} never degenerates to {@code IN ()});
   * the e-mail query runs only for a non-blank e-mail.
   *
   * @param username the incoming Discord username; may be {@code null}/blank
   * @param email the incoming Discord e-mail; may be {@code null}/blank
   * @param serverNickname the incoming per-guild server nickname; may be {@code null}/blank
   * @return {@code true} iff at least one existing account matches any candidate
   */
  @Transactional(readOnly = true)
  public boolean accountExistsForDiscordIdentity(
      @Nullable String username, @Nullable String email, @Nullable String serverNickname) {
    Set<String> lowerNames = new HashSet<>();
    addNormalized(lowerNames, username);
    addNormalized(lowerNames, serverNickname);
    String lowerEmail = normalize(email);

    boolean byName =
        !lowerNames.isEmpty() && userRepository.existsByLowerUsernameOrDisplayNameIn(lowerNames);
    boolean byEmail = lowerEmail != null && userRepository.existsByLowerEmail(lowerEmail);

    boolean exists = byName || byEmail;
    // REQ-OBS: log only the coarse decision, never the candidate names/e-mail.
    log.debug("Discord account-existence precheck decided exists={}.", exists);
    return exists;
  }

  /**
   * Adds {@code value} to {@code target} normalised (trimmed + lower-cased), skipping it when it
   * normalises to {@code null} (blank/empty).
   *
   * @param target the accumulating candidate-name set
   * @param value the raw candidate; may be {@code null}/blank
   */
  private static void addNormalized(@NotNull Set<String> target, @Nullable String value) {
    String normalized = normalize(value);
    if (normalized != null) {
      target.add(normalized);
    }
  }

  /**
   * Trims and lower-cases a raw candidate to match the {@code LOWER(...)} comparison run in the
   * repository, mapping blank/empty to {@code null}.
   *
   * @param value the raw candidate; may be {@code null}
   * @return the trimmed, lower-cased value, or {@code null} when blank/empty
   */
  @Contract("null -> null")
  @Nullable
  private static String normalize(@Nullable String value) {
    String trimmed = StringNormalization.trimToNull(value);
    return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
  }
}
