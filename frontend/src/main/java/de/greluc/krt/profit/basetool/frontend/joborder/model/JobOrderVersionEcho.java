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

package de.greluc.krt.profit.basetool.frontend.joborder.model;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A job-order form that echoes the versions its edit observed. The form binds them as the flat
 * {@code version} and {@code commentVersion} fields the page posts, and hands them on only as one
 * {@link JobOrderObservedVersions}.
 */
public interface JobOrderVersionEcho {

  /**
   * The order version the form echoes.
   *
   * @return the bound order version, {@code null} on a create
   */
  @Nullable
  Long getVersion();

  /**
   * Binds the order version the page posted.
   *
   * @param version the observed order version
   */
  void setVersion(@Nullable Long version);

  /**
   * The comment version the form echoes beside the order version.
   *
   * @return the bound comment version, {@code null} on a create
   */
  @Nullable
  Long getCommentVersion();

  /**
   * Binds the comment version the page posted.
   *
   * @param commentVersion the observed comment version
   */
  void setCommentVersion(@Nullable Long commentVersion);

  /**
   * The two bound versions as the pair a create or edit payload carries.
   *
   * @return the order version and the comment version, in their own slots
   */
  @Contract(" -> new")
  default @NotNull JobOrderObservedVersions observedVersions() {
    return new JobOrderObservedVersions(getVersion(), getCommentVersion());
  }

  /**
   * Prefills both versions an edit starts from.
   *
   * @param observed the order version and the comment version the page read
   */
  default void observe(@NotNull JobOrderObservedVersions observed) {
    setVersion(observed.version());
    setCommentVersion(observed.commentVersion());
  }
}
