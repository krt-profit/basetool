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
 * Read-only check whether a Basetool account already matches an incoming Discord identity, backing
 * the first-broker-login collision gate (REQ-SEC-022).
 *
 * <p>Name candidates are matched case-insensitively against {@code username} and {@code
 * displayName}, the e-mail against the account e-mail. Only a boolean is returned and no candidate
 * value is logged.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DiscordAccountExistenceService {

  private final UserRepository userRepository;

  /**
   * Decides whether an existing account collides with the supplied Discord identity. Inputs are
   * trimmed and lower-cased; blank or {@code null} candidates are ignored.
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
