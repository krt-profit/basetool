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
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional reads and writes of the dashboards; {@link DashboardService} wraps them with
 * the write metrics.
 *
 * <p>A layout write bumps the mode's layout version with a conditional {@code UPDATE} first, which
 * both rejects a stale version and locks the layout row until the commit. An option write bumps
 * only the instance's option version, so it never collides with a layout write.
 */
@Service
@RequiredArgsConstructor
public class DashboardWriter {

  private final NavigationPreferencesRepository preferences;
  private final DashboardLayoutRepository layouts;
  private final DashboardWidgetRepository widgets;

  /**
   * Reads a mode's dashboard; a member who never changed it holds the mode's default.
   *
   * @param userId the owner's {@code app_user.id}
   * @param mode the mode
   * @return the dashboard with its layout version
   */
  @Transactional(readOnly = true)
  public @NotNull DashboardResponse read(@NotNull UUID userId, @NotNull DashboardMode mode) {
    return layouts
        .findByPreferencesIdAndMode(userId, mode)
        .map(this::view)
        .orElseGet(() -> defaults(mode, 0));
  }

  /**
   * Replaces a mode's layout when the echoed version is current.
   *
   * @param userId the owner's {@code app_user.id}
   * @param mode the mode
   * @param request the instances in display order and the layout version the caller last saw
   * @return the stored dashboard with the bumped version
   * @throws BadRequestException when the layout breaks a rule
   * @throws ObjectOptimisticLockingFailureException when the version is stale
   */
  @Transactional
  public @NotNull DashboardResponse replaceLayout(
      @NotNull UUID userId, @NotNull DashboardMode mode, @NotNull DashboardLayoutRequest request) {
    validate(mode, request.widgets());
    NavigationDashboardLayout layout = lockLayout(userId, mode, request.version(), true);
    Map<UUID, NavigationDashboardWidget> stored =
        widgets.findByLayoutIdOrderByPositionAsc(layout.getId()).stream()
            .collect(Collectors.toMap(NavigationDashboardWidget::getId, Function.identity()));
    List<NavigationDashboardWidget> kept = new ArrayList<>();
    int position = 0;
    for (DashboardLayoutItem item : request.widgets()) {
      NavigationDashboardWidget instance =
          item.id() == null
              ? new NavigationDashboardWidget(layout.getId(), item.type(), item.column(), position)
              : stored.remove(item.id());
      if (instance == null || instance.getType() != item.type()) {
        throw new BadRequestException("The layout names an instance this dashboard does not hold.");
      }
      instance.moveTo(item.column(), position++);
      kept.add(instance);
    }
    widgets.deleteAll(stored.values());
    widgets.saveAllAndFlush(kept);
    return storedView(layout.getId(), mode, layout.getLayoutVersion(), true);
  }

  /**
   * Replaces the options of one instance when the echoed option version is current.
   *
   * @param userId the owner's {@code app_user.id}
   * @param mode the mode
   * @param widgetId the instance
   * @param request the new options and the option version the caller last saw
   * @return the stored instance with the bumped option version
   * @throws NotFoundException when the member's dashboard holds no such instance
   * @throws BadRequestException when the options do not fit the widget type
   * @throws ObjectOptimisticLockingFailureException when the version is stale
   */
  @Transactional
  public @NotNull DashboardWidgetResponse replaceOptions(
      @NotNull UUID userId,
      @NotNull DashboardMode mode,
      @NotNull UUID widgetId,
      @NotNull DashboardOptionsRequest request) {
    String missing = "No dashboard widget " + widgetId;
    NavigationDashboardLayout layout =
        Entities.require(layouts.findByPreferencesIdAndMode(userId, mode), missing);
    NavigationDashboardWidget widget =
        Entities.require(widgets.findByIdAndLayoutId(widgetId, layout.getId()), missing);
    DashboardOptions options = withDefaults(widget.getType(), request);
    String violation = widget.getType().violation(options);
    if (violation != null) {
      throw new BadRequestException(violation);
    }
    if (widgets.bumpOptionsVersionIfMatches(widgetId, request.version()) == 0) {
      throw new ObjectOptimisticLockingFailureException(NavigationDashboardWidget.class, widgetId);
    }
    NavigationDashboardWidget stored = widgets.findById(widgetId).orElseThrow();
    stored.setOptions(options);
    return widgetResponse(widgets.saveAndFlush(stored));
  }

  /**
   * Restores a mode's default when the echoed version is current.
   *
   * @param userId the owner's {@code app_user.id}
   * @param mode the mode
   * @param expectedVersion the layout version the caller last saw
   * @return the default dashboard with the bumped version
   * @throws ObjectOptimisticLockingFailureException when the version is stale
   */
  @Transactional
  public @NotNull DashboardResponse reset(
      @NotNull UUID userId, @NotNull DashboardMode mode, long expectedVersion) {
    NavigationDashboardLayout layout = lockLayout(userId, mode, expectedVersion, false);
    widgets.deleteAllOfLayout(layout.getId());
    return defaults(mode, layout.getLayoutVersion());
  }

  /**
   * Deletes the instances of the given types from a mode, bumping the layout version when one went.
   *
   * @param userId the owner's {@code app_user.id}
   * @param mode the mode
   * @param types the widget types the member may no longer use
   * @return {@code true} when a stored instance was deleted
   */
  @Transactional
  public boolean prune(
      @NotNull UUID userId, @NotNull DashboardMode mode, @NotNull Set<DashboardWidgetType> types) {
    return layouts
        .findByPreferencesIdAndMode(userId, mode)
        .map(layout -> pruneLayout(layout, types))
        .orElse(false);
  }

  private boolean pruneLayout(NavigationDashboardLayout layout, Set<DashboardWidgetType> types) {
    List<NavigationDashboardWidget> gone =
        widgets.findByLayoutIdOrderByPositionAsc(layout.getId()).stream()
            .filter(widget -> types.contains(widget.getType()))
            .toList();
    if (gone.isEmpty()) {
      return false;
    }
    widgets.deleteAll(gone);
    widgets.flush();
    layouts.bump(layout.getId());
    return true;
  }

  private NavigationDashboardLayout lockLayout(
      UUID userId, DashboardMode mode, long expected, boolean customised) {
    preferences.insertIfAbsent(userId);
    layouts.insertIfAbsent(UUID.randomUUID(), userId, mode.name());
    if (layouts.bumpIfMatches(userId, mode.name(), expected, customised) == 0) {
      throw new ObjectOptimisticLockingFailureException(NavigationDashboardLayout.class, userId);
    }
    return layouts.findByPreferencesIdAndMode(userId, mode).orElseThrow();
  }

  private static void validate(DashboardMode mode, List<DashboardLayoutItem> items) {
    if (items.size() > NavigationLimits.MAX_DASHBOARD_WIDGETS) {
      throw new BadRequestException(
          "A dashboard holds at most " + NavigationLimits.MAX_DASHBOARD_WIDGETS + " instances.");
    }
    Set<UUID> ids = new HashSet<>();
    for (DashboardLayoutItem item : items) {
      if (item.type().getMode() != mode) {
        throw new BadRequestException("The widget " + item.type() + " does not belong to " + mode);
      }
      if (item.id() != null && !ids.add(item.id())) {
        throw new BadRequestException("A dashboard instance may appear only once.");
      }
    }
  }

  private static DashboardOptions withDefaults(
      DashboardWidgetType type, DashboardOptionsRequest request) {
    DashboardOptions defaults = type.getDefaults();
    return new DashboardOptions(
        request.orgUnitId(),
        request.days() != null ? request.days() : defaults.days(),
        request.rows() != null ? request.rows() : defaults.rows(),
        request.status() != null ? request.status() : defaults.status());
  }

  private DashboardResponse view(NavigationDashboardLayout layout) {
    return layout.isCustomised()
        ? storedView(layout.getId(), layout.getMode(), layout.getLayoutVersion(), true)
        : defaults(layout.getMode(), layout.getLayoutVersion());
  }

  private DashboardResponse storedView(
      UUID layoutId, DashboardMode mode, long version, boolean customised) {
    return new DashboardResponse(
        mode,
        version,
        customised,
        widgets.findByLayoutIdOrderByPositionAsc(layoutId).stream()
            .map(DashboardWriter::widgetResponse)
            .toList());
  }

  private static DashboardResponse defaults(DashboardMode mode, long version) {
    return new DashboardResponse(
        mode,
        version,
        false,
        DashboardWidgetType.defaultsOf(mode).stream()
            .map(type -> {
              DashboardOptions options = type.getDefaults();
              return new DashboardWidgetResponse(
                  null,
                  type,
                  type.getDefaultColumn(),
                  0,
                  options.orgUnitId(),
                  options.days(),
                  options.rows(),
                  options.status());
            })
            .toList());
  }

  private static DashboardWidgetResponse widgetResponse(NavigationDashboardWidget widget) {
    return new DashboardWidgetResponse(
        widget.getId(),
        widget.getType(),
        widget.getColumn(),
        widget.getOptionsVersion(),
        widget.getOrgUnitId(),
        widget.getDays(),
        widget.getRows(),
        widget.getStatus());
  }
}
