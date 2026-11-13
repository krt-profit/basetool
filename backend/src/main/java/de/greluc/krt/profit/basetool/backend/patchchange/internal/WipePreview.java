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

package de.greluc.krt.profit.basetool.backend.patchchange.internal;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Breaks a wipe preview down per group and per exception (REQ-PATCH-016), from the same survey the
 * effects count from.
 */
@Component
@RequiredArgsConstructor
class WipePreview {

  private final WipeSurvey survey;
  private final WipeExceptionCatalogue catalogue;

  /**
   * Breaks down what a plan's wipe would touch now.
   *
   * @param plan the live time and options under preview
   * @return one count per wipe group and per exception
   */
  @NotNull
  WipePreviewDto breakdown(@NotNull PatchChangePlan plan) {
    WipeSurvey.Result result = survey.survey(plan);
    WipeSelection selection = result.selection();
    List<WipePreviewDto.GroupCount> groups =
        Arrays.stream(WipeGroup.values())
            .map(group -> {
              if (!group.selectsStock()) {
                return new WipePreviewDto.GroupCount(
                    group, result.ships().ships(), result.ships().owners());
              }
              WipeSelection.Tally tally = selection.groupTally(group);
              return new WipePreviewDto.GroupCount(group, tally.rows(), tally.members());
            })
            .toList();
    WipeExceptionCatalogue.Names names = catalogue.names(plan.options().wipeExceptions());
    List<WipePreviewDto.ExceptionCount> exceptions =
        plan.options().wipeExceptions().stream()
            .map(exception -> {
              WipeSelection.Tally tally = selection.exceptionTally(exception);
              return new WipePreviewDto.ExceptionCount(
                  WipeExceptionDto.of(exception, names.of(exception)),
                  tally.rows(),
                  tally.members());
            })
            .sorted(
                Comparator.comparing(
                    count -> Objects.requireNonNullElse(count.exception().name(), "")))
            .toList();
    return new WipePreviewDto(groups, exceptions);
  }
}
