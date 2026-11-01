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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;

/**
 * One widget instance of a dashboard layout: its type, column, position and options (REQ-UI-034).
 * The option version is bumped by a conditional {@code UPDATE}, so an option change never collides
 * with a layout change.
 */
@Entity
@Table(name = "navigation_dashboard_widget")
@Getter
@NoArgsConstructor
public class NavigationDashboardWidget {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "layout_id", nullable = false, updatable = false)
  private UUID layoutId;

  @Enumerated(EnumType.STRING)
  @Column(name = "widget_type", nullable = false, updatable = false, length = 32)
  private DashboardWidgetType type;

  @Enumerated(EnumType.STRING)
  @Column(name = "column_side", nullable = false, length = 8)
  private DashboardColumn column;

  @Column(name = "position", nullable = false)
  private int position;

  @Column(name = "options_version", nullable = false, insertable = false, updatable = false)
  private long optionsVersion;

  @Column(name = "org_unit_id")
  private UUID orgUnitId;

  @Column(name = "range_days")
  private Integer days;

  @Column(name = "row_count")
  private Integer rows;

  @Enumerated(EnumType.STRING)
  @Column(name = "status_filter", length = 16)
  private DashboardStatus status;

  /**
   * Creates an instance with the type's default options.
   *
   * @param layoutId the owning layout
   * @param type the widget type
   * @param column the column
   * @param position the position in the layout
   */
  public NavigationDashboardWidget(
      @NotNull UUID layoutId,
      @NotNull DashboardWidgetType type,
      @NotNull DashboardColumn column,
      int position) {
    this.id = UUID.randomUUID();
    this.layoutId = layoutId;
    this.type = type;
    this.column = column;
    this.position = position;
    setOptions(type.getDefaults());
  }

  /**
   * Moves the instance.
   *
   * @param column the column
   * @param position the position in the layout
   */
  public void moveTo(@NotNull DashboardColumn column, int position) {
    this.column = column;
    this.position = position;
  }

  /**
   * Replaces the options.
   *
   * @param options the new options
   */
  public void setOptions(@NotNull DashboardOptions options) {
    this.orgUnitId = options.orgUnitId();
    this.days = options.days();
    this.rows = options.rows();
    this.status = options.status();
  }
}
