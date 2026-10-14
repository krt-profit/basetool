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

package de.greluc.krt.profit.basetool.backend.purchasing.internal;

import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.platform.api.OwnedRows;
import de.greluc.krt.profit.basetool.backend.platform.api.OwnedRowsMover;
import de.greluc.krt.profit.basetool.backend.platform.api.OwnedRowsReassignment;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The purchasing module's part of the account merge: the deals, the appointments and the authorship
 * of partners, deliveries and settlements follow the member. Two accounts that both hold an active
 * appointment cannot merge, because a member holds one at a time.
 */
@Component
public class PurchasingUserReassignment extends OwnedRowsReassignment {

  static final int POSITION = 200;

  private final PurchasingDelegateRepository delegateRepository;

  /**
   * Declares the module's columns.
   *
   * @param mover the engine that runs the statements
   * @param delegateRepository the appointments, to refuse a merge of two active delegates
   */
  public PurchasingUserReassignment(
      @NotNull OwnedRowsMover mover, @NotNull PurchasingDelegateRepository delegateRepository) {
    super(
        mover,
        POSITION,
        List.of(
            OwnedRows.of("purchasing_delegate", "user_id"),
            OwnedRows.of("purchasing_delegate", "appointed_by"),
            OwnedRows.of("purchasing_delegate", "revoked_by"),
            OwnedRows.of("purchasing_partner", "created_by"),
            OwnedRows.of("purchasing_deal", "delegate_user_id"),
            OwnedRows.of("purchasing_deal_delivery", "recorded_by"),
            OwnedRows.of("purchasing_deal_delivery", "accepted_by"),
            OwnedRows.of("purchasing_deal_settlement", "recorded_by"),
            OwnedRows.of("purchasing_setting", "paused_by"),
            OwnedRows.of("purchasing_unit_pause", "paused_by")),
        List.of());
    this.delegateRepository = delegateRepository;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void assertMergeable(@NotNull UUID source, @NotNull UUID target) {
    if (delegateRepository.findFirstByUserIdAndRevokedAtIsNull(source).isPresent()
        && delegateRepository.findFirstByUserIdAndRevokedAtIsNull(target).isPresent()) {
      throw new BadRequestException(
          "Both accounts hold an active trade-delegate appointment; revoke one first");
    }
  }
}
