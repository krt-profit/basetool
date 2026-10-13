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

package de.greluc.krt.profit.basetool.backend.mission.api;

import de.greluc.krt.profit.basetool.backend.identity.api.PayoutPreference;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes a member's default payout preference (REQ-MISSION-002), which pre-fills the
 * payout choice at mission sign-up. A member without a row has made no explicit choice and is read
 * at version 0, the version the first written row starts at.
 */
@Service
@RequiredArgsConstructor
public class MemberPayoutSettings {

  private final MemberPayoutSettingRepository repository;
  private final UserRepository userRepository;

  /**
   * The stored preference with the row version a client echoes back on its next write.
   *
   * @param preference the stored preference, or {@code null} when the member made no choice
   * @param version the row version, 0 while no row exists
   */
  public record Snapshot(@Nullable PayoutPreference preference, long version) {}

  /**
   * Reads a member's setting.
   *
   * @param userId the member; never {@code null}
   * @return the stored preference and the version to echo back
   */
  @Transactional(readOnly = true)
  public @NotNull Snapshot snapshotOf(@NotNull UUID userId) {
    return repository
        .findById(userId)
        .map(s -> new Snapshot(s.getDefaultPayoutPreference(), versionOf(s)))
        .orElseGet(() -> new Snapshot(null, 0L));
  }

  /**
   * Reads only the preference, for the mission sign-up pre-fill.
   *
   * @param userId the member; never {@code null}
   * @return the stored preference, empty when the member made no choice
   */
  @Transactional(readOnly = true)
  public @NotNull Optional<PayoutPreference> defaultFor(@NotNull UUID userId) {
    return repository.findById(userId).map(MemberPayoutSetting::getDefaultPayoutPreference);
  }

  /**
   * Sets a member's default payout preference; existing participations are not changed.
   *
   * @param userId the member; never {@code null}
   * @param preference the new default; never {@code null}
   * @param version the version the caller last read; {@code null} skips the check, 0 matches a
   *     member without a row
   * @return the persisted preference and the new version
   * @throws NotFoundException when the member is unknown
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the version is
   *     stale
   */
  @Transactional
  public @NotNull Snapshot update(
      @NotNull UUID userId, @NotNull PayoutPreference preference, @Nullable Long version) {
    if (!userRepository.existsById(userId)) {
      throw new NotFoundException("User not found");
    }
    MemberPayoutSetting setting = repository.findById(userId).orElse(null);
    OptimisticLock.checkOptionalClient(
        setting == null ? Long.valueOf(0L) : setting.getVersion(),
        version,
        MemberPayoutSetting.class,
        userId);
    if (setting == null) {
      setting = new MemberPayoutSetting();
      setting.setId(userId);
    }
    setting.setDefaultPayoutPreference(preference);
    MemberPayoutSetting saved = repository.saveAndFlush(setting);
    return new Snapshot(saved.getDefaultPayoutPreference(), versionOf(saved));
  }

  private static long versionOf(@NotNull MemberPayoutSetting setting) {
    Long version = setting.getVersion();
    return version == null ? 0L : version;
  }
}
