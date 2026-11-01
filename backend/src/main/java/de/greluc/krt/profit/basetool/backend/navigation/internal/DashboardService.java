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

package de.greluc.krt.profit.basetool.backend.navigation.internal;

import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * The caller's own dashboards (REQ-UI-034): owner-only, unaudited and not pushed live. Every write
 * is counted once it has committed or been refused, so the service itself holds no transaction.
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

  private final DashboardWriter writer;
  private final NavigationMetrics metrics;

  /**
   * Reads a mode's dashboard.
   *
   * @param userId the caller's {@code app_user.id}
   * @param mode the mode
   * @return the dashboard; the mode's default while the caller never changed it
   */
  public @NotNull DashboardResponse read(@NotNull UUID userId, @NotNull DashboardMode mode) {
    return writer.read(userId, mode);
  }

  /**
   * Replaces a mode's layout.
   *
   * @param userId the caller's {@code app_user.id}
   * @param mode the mode
   * @param request the instances in display order and the layout version the caller last saw
   * @return the stored dashboard
   * @throws ObjectOptimisticLockingFailureException when the version is stale
   * @throws BadRequestException when the layout breaks a rule
   */
  public @NotNull DashboardResponse replaceLayout(
      @NotNull UUID userId, @NotNull DashboardMode mode, @NotNull DashboardLayoutRequest request) {
    return counted(NavigationSection.DASHBOARD, () -> writer.replaceLayout(userId, mode, request));
  }

  /**
   * Replaces the options of one instance.
   *
   * @param userId the caller's {@code app_user.id}
   * @param mode the mode
   * @param widgetId the instance
   * @param request the new options and the option version the caller last saw
   * @return the stored instance
   * @throws ObjectOptimisticLockingFailureException when the version is stale
   * @throws BadRequestException when the options do not fit the widget type
   */
  public @NotNull DashboardWidgetResponse replaceOptions(
      @NotNull UUID userId,
      @NotNull DashboardMode mode,
      @NotNull UUID widgetId,
      @NotNull DashboardOptionsRequest request) {
    return counted(
        NavigationSection.DASHBOARD, () -> writer.replaceOptions(userId, mode, widgetId, request));
  }

  /**
   * Restores a mode's default.
   *
   * @param userId the caller's {@code app_user.id}
   * @param mode the mode
   * @param version the layout version the caller last saw
   * @return the default dashboard
   * @throws ObjectOptimisticLockingFailureException when the version is stale
   */
  public @NotNull DashboardResponse reset(
      @NotNull UUID userId, @NotNull DashboardMode mode, long version) {
    return counted(NavigationSection.DASHBOARD, () -> writer.reset(userId, mode, version));
  }

  /**
   * Deletes the instances of widget types the caller may no longer use.
   *
   * @param userId the caller's {@code app_user.id}
   * @param mode the mode
   * @param types the widget types
   * @return the dashboard after the deletion
   */
  public @NotNull DashboardResponse prune(
      @NotNull UUID userId, @NotNull DashboardMode mode, @NotNull Set<DashboardWidgetType> types) {
    if (writer.prune(userId, mode, types)) {
      metrics.recordWrite(NavigationSection.DASHBOARD, NavigationWriteOutcome.OK);
    }
    return writer.read(userId, mode);
  }

  private <T> T counted(NavigationSection section, Supplier<T> write) {
    try {
      T stored = write.get();
      metrics.recordWrite(section, NavigationWriteOutcome.OK);
      return stored;
    } catch (ObjectOptimisticLockingFailureException e) {
      metrics.recordWrite(section, NavigationWriteOutcome.CONFLICT);
      throw e;
    } catch (BadRequestException e) {
      metrics.recordWrite(section, NavigationWriteOutcome.REJECTED);
      throw e;
    }
  }
}
