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

package de.greluc.krt.profit.basetool.backend.promotion.internal;

import de.greluc.krt.profit.basetool.backend.platform.api.PersonMentionSource;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The promotion's part of the person search: the columns of the promotion catalogue that can name a
 * person, and those left out (REQ-SEC-060).
 */
@Component
public class PromotionMentionSource implements PersonMentionSource {

  @Override
  public @NotNull List<Target> targets() {
    return List.of(
        new Target("PROMOTION", "promotion_category", "description", "id", null),
        new Target("PROMOTION", "promotion_category", "name", "id", null),
        new Target("PROMOTION", "promotion_level_content", "description", "id", null),
        new Target("PROMOTION", "promotion_topic", "description", "id", null),
        new Target("PROMOTION", "promotion_topic", "name", "id", null),
        new Target("PROMOTION", "rank_requirement", "description", "id", null));
  }

  @Override
  public @NotNull Set<String> exemptColumns() {
    return Set.of(
        "member_evaluation.assigned_level",
        "promotion_level_content.level",
        "rank_requirement.minimum_level");
  }
}
