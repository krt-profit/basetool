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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes the terminal price a member's "Mein Lager" values the own materials at
 * (REQ-INV-072). A member without a row values at {@link SalePriceBasis#AVERAGE} and is read at
 * version 0, the version the first written row starts at.
 */
@Service
@RequiredArgsConstructor
public class MemberSaleValueSettings {

  /** The basis of a member who made no choice. */
  static final SalePriceBasis DEFAULT_BASIS = SalePriceBasis.AVERAGE;

  private final MemberSaleValueSettingRepository repository;
  private final UserRepository userRepository;

  /**
   * The basis in effect with the row version a client echoes back on its next write.
   *
   * @param basis the stored basis, or {@link #DEFAULT_BASIS} when the member made no choice
   * @param version the row version, 0 while no row exists
   */
  public record Snapshot(@NotNull SalePriceBasis basis, long version) {}

  /**
   * Reads a member's setting.
   *
   * @param userId the member; never {@code null}
   * @return the basis in effect and the version to echo back
   */
  @Transactional(readOnly = true)
  public @NotNull Snapshot snapshotOf(@NotNull UUID userId) {
    return repository
        .findById(userId)
        .map(setting -> new Snapshot(setting.getSalePriceBasis(), versionOf(setting)))
        .orElseGet(() -> new Snapshot(DEFAULT_BASIS, 0L));
  }

  /**
   * Reads only the basis, for the valuation of the member's grouped listing.
   *
   * @param userId the member; never {@code null}
   * @return the stored basis, or {@link #DEFAULT_BASIS} when the member made no choice
   */
  @Transactional(readOnly = true)
  public @NotNull SalePriceBasis basisFor(@NotNull UUID userId) {
    return repository
        .findById(userId)
        .map(MemberSaleValueSetting::getSalePriceBasis)
        .orElse(DEFAULT_BASIS);
  }

  /**
   * Sets the basis a member's "Mein Lager" values at.
   *
   * @param userId the member; never {@code null}
   * @param basis the new basis; never {@code null}
   * @param version the version the caller last read; {@code null} skips the check, 0 matches a
   *     member without a row
   * @return the persisted basis and the new version
   * @throws NotFoundException when the member is unknown
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the version is
   *     stale
   */
  @Transactional
  public @NotNull Snapshot update(
      @NotNull UUID userId, @NotNull SalePriceBasis basis, @Nullable Long version) {
    if (!userRepository.existsById(userId)) {
      throw new NotFoundException("User not found");
    }
    MemberSaleValueSetting setting = repository.findById(userId).orElse(null);
    OptimisticLock.checkOptionalClient(
        setting == null ? Long.valueOf(0L) : setting.getVersion(),
        version,
        MemberSaleValueSetting.class,
        userId);
    if (setting == null) {
      setting = new MemberSaleValueSetting();
      setting.setId(userId);
    }
    setting.setSalePriceBasis(basis);
    MemberSaleValueSetting saved = repository.saveAndFlush(setting);
    return new Snapshot(saved.getSalePriceBasis(), versionOf(saved));
  }

  /**
   * The version of a row, 0 for one not yet flushed.
   *
   * @param setting the row
   * @return its version
   */
  private static long versionOf(@NotNull MemberSaleValueSetting setting) {
    Long version = setting.getVersion();
    return version == null ? 0L : version;
  }
}
