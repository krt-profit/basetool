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

package de.greluc.krt.profit.basetool.backend.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties under {@code app.android.*} — what the server tells the Android app
 * about which builds it still serves (REQ-API-010, app issue #67).
 *
 * <p>Deliberately configuration rather than a table. Raising the floor is an operational act taken
 * at the moment a contract breaks, and it must be possible without a schema migration, an admin
 * screen or a deploy — an env var and a restart. There is also nothing to query: the whole policy
 * is three scalars that are the same for every caller, so a row per organisation would be a join
 * that always returns the same answer.
 *
 * <p><b>The default floor is deliberately 0.</b> A server that has never been configured must not
 * lock every member out of the app, which is what any non-zero default would do the first time this
 * code reaches an environment whose operator has not thought about it yet. Locking members out is
 * the expensive direction of a wrong guess; serving an old build for one more day is the cheap one.
 */
@Data
@Validated
@Configuration
@ConfigurationProperties(prefix = "app.android")
public class AndroidClientProperties {

  /**
   * The oldest {@code versionCode} the server still serves. A build below it is refused by the app
   * itself, which shows the non-dismissible „Update erforderlich" screen (design chapter 14).
   *
   * <p>Zero means "no floor" and is the default, for the reason given on the class.
   */
  @NotNull
  @Min(0)
  private Integer minimumVersionCode = 0;

  /**
   * The newest {@code versionCode} published, or {@code 0} when unknown.
   *
   * <p>This is <em>not</em> what the gate keys on — it exists so the app can tell "you must update"
   * from "an update exists", and only the first of those is allowed to block anyone. Keeping the
   * two numbers apart is what stops a routine release from reading as a forced one.
   */
  @NotNull
  @Min(0)
  private Integer latestVersionCode = 0;

  /**
   * Where the member gets the new build. Distribution is GitHub Releases plus Obtainium (plan Q1),
   * not a store, so the design's Play-Store CTA does not apply and this is the recorded deviation:
   * the button opens the release page.
   */
  @NotBlank
  private String releasesUrl = "https://github.com/krt-profit/basetool-android/releases/latest";
}
