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

package de.greluc.krt.profit.basetool.backend.refinery.internal;

import de.greluc.krt.profit.basetool.backend.platform.api.PersonMentionSource;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The refinery's part of the person search: the columns of refinery orders that can name a person,
 * and those left out (REQ-SEC-060). The status and the provenance sources are enum codes, the
 * provenance clients are exchange-registry client ids the server writes (REQ-REFINERY-027); none
 * holds text a person typed.
 */
@Component
public class RefineryMentionSource implements PersonMentionSource {

  @Override
  public @NotNull List<Target> targets() {
    return List.of();
  }

  @Override
  public @NotNull Set<String> exemptColumns() {
    return Set.of(
        "refinery_order.created_client",
        "refinery_order.created_source",
        "refinery_order.last_change_client",
        "refinery_order.last_change_source",
        "refinery_order.picked_up_client",
        "refinery_order.status");
  }
}
