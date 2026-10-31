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

package de.greluc.krt.profit.basetool.frontend.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;

/**
 * Reads the constants of the backend's {@code NotificationType} from its source file, since the
 * frontend holds no backend classes.
 */
public final class BackendNotificationTypes {

  /** The backend enum's source file. */
  private static final Path NOTIFICATION_TYPE =
      resolveModuleRelative(
          "../backend/src/main/java/de/greluc/krt/profit/basetool/backend/notification/api/"
              + "NotificationType.java");

  /** An enum constant: a bare upper-snake identifier at the start of a line, inside the body. */
  private static final Pattern CONSTANT =
      Pattern.compile("^\\s{2}([A-Z][A-Z0-9_]*)\\s*(?:,|;|$)", Pattern.MULTILINE);

  private BackendNotificationTypes() {}

  /**
   * The enum's constants, read from the backend source.
   *
   * @return the constant names in declaration order, never empty
   * @throws IOException when the source file cannot be read
   */
  public static @NotNull Set<String> all() throws IOException {
    assertThat(Files.isRegularFile(NOTIFICATION_TYPE))
        .as("NotificationType.java not found at " + NOTIFICATION_TYPE.toAbsolutePath())
        .isTrue();
    String source = Files.readString(NOTIFICATION_TYPE, StandardCharsets.UTF_8);
    int body = source.indexOf("public enum NotificationType");
    assertThat(body).as("the enum declaration").isGreaterThan(0);

    Set<String> out = new LinkedHashSet<>();
    Matcher m = CONSTANT.matcher(source.substring(body));
    while (m.find()) {
      out.add(m.group(1));
    }
    assertThat(out)
        .as("parsed NotificationType constants from " + NOTIFICATION_TYPE.toAbsolutePath())
        .isNotEmpty();
    return out;
  }

  /**
   * Resolves a path given relative to the frontend module root, tolerating a run from the
   * repository root.
   *
   * @param relative the module-relative path
   * @return the path that exists, or the module-relative one when neither does
   */
  public static @NotNull Path resolveModuleRelative(@NotNull String relative) {
    Path direct = Paths.get(relative);
    if (Files.exists(direct)) {
      return direct;
    }
    Path fromRepoRoot = Paths.get("frontend").resolve(relative);
    if (Files.exists(fromRepoRoot)) {
      return fromRepoRoot;
    }
    return direct;
  }
}
