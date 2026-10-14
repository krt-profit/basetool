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

package de.greluc.krt.profit.basetool.backend.supply.internal;

import de.greluc.krt.profit.basetool.backend.platform.api.PersonMentionSource;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The supply module's part of the person search: it stores no free text a person could be named in,
 * so every text column is a code and left out (REQ-SEC-060, REQ-SUP-014).
 */
@Component
public class SupplyMentionSource implements PersonMentionSource {

  @Override
  public @NotNull List<Target> targets() {
    return List.of();
  }

  @Override
  public @NotNull Set<String> exemptColumns() {
    return Set.of(
        "supply_need.status",
        "supply_need.priority",
        "supply_need.purchase_reason",
        "supply_donation.status",
        "supply_donation.decline_reason");
  }
}
