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

package de.greluc.krt.profit.basetool.frontend.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Every {@code NotificationType} the backend can persist must have a message template in all three
 * bundles (REQ-NOTIF-001, the root i18n rule).
 *
 * <p><b>Why the existing bundle test cannot catch this.</b> {@code MessageBundleConsistencyTest}
 * compares the three bundles against each other and scans templates for keys they reference. The
 * key here is built at runtime in Java — {@code "notifications.type." + dto.type()} — so a missing
 * one is consistent across all three bundles and appears in no template. It fails silently: {@code
 * NotificationPageController} falls through to {@code notifications.type.generic}, and the member's
 * bell shows "Neue Benachrichtigung" with the render parameters dropped.
 *
 * <p>That is how {@code ACCOUNT_DELETION_REQUESTED} and {@code ACCOUNT_DELETION_REQUEST_DECLINED}
 * shipped contentless: every admin's inbox said nothing when a member asked to be erased — no
 * indication that the Art. 12(3) one-month clock had started — and the declined member, whom Art.
 * 12(4) obliges the controller to inform, got the same. The audit-viewer labels for the same two
 * events <em>were</em> added, which is what made the gap look like it was not there.
 *
 * <p>The enum is read from the backend's source rather than imported: the frontend module holds no
 * backend classes, and {@code NotificationDto.type} is a plain {@code String} on the wire. Reading
 * the file is the same approach {@code DtoMirrorConsistencyTest} takes for the DTO mirrors.
 */
class NotificationTypeMessageCoverageTest {

  /** The enum whose constants must each have a template. */
  private static final Path NOTIFICATION_TYPE =
      resolveModuleRelative(
          "../backend/src/main/java/de/greluc/krt/profit/basetool/backend/model/"
              + "NotificationType.java");

  /** The three bundles, by the name a failure should name. */
  private static final Map<String, Path> BUNDLES =
      Map.of(
          "messages.properties",
          resolveModuleRelative("src/main/resources/messages.properties"),
          "messages_de.properties",
          resolveModuleRelative("src/main/resources/messages_de.properties"),
          "messages_en.properties",
          resolveModuleRelative("src/main/resources/messages_en.properties"));

  /** An enum constant: a bare upper-snake identifier at the start of a line, inside the body. */
  private static final Pattern CONSTANT =
      Pattern.compile("^\\s{2}([A-Z][A-Z0-9_]*)\\s*(?:,|;|$)", Pattern.MULTILINE);

  @Test
  void everyNotificationTypeHasATemplateInEveryBundle() throws IOException {
    Set<String> constants = notificationTypes();
    assertThat(constants)
        .as("parsed NotificationType constants from " + NOTIFICATION_TYPE.toAbsolutePath())
        .isNotEmpty();

    Map<String, List<String>> missingByBundle = new LinkedHashMap<>();
    for (Map.Entry<String, Path> bundle : BUNDLES.entrySet()) {
      String content = Files.readString(bundle.getValue(), StandardCharsets.UTF_8);
      List<String> missing = new ArrayList<>();
      for (String constant : constants) {
        if (!content.contains("notifications.type." + constant + "=")) {
          missing.add(constant);
        }
      }
      if (!missing.isEmpty()) {
        missingByBundle.put(bundle.getKey(), missing);
      }
    }

    assertThat(missingByBundle)
        .as(
            "Each of these notification types can reach a member's inbox with no message of its"
                + " own, so the bell renders notifications.type.generic and drops the render"
                + " parameters. Add notifications.type.<TYPE> to the bundle named here -- umlauts"
                + " as \\\\uXXXX in the .properties files.")
        .isEmpty();
  }

  /**
   * The enum's constants, read from the backend source.
   *
   * @return the constant names in declaration order
   * @throws IOException when the source file cannot be read
   */
  private static Set<String> notificationTypes() throws IOException {
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
    return out;
  }

  /**
   * Resolves a path given relative to the frontend module root, tolerating a run from the
   * repository root.
   *
   * @param relative the module-relative path
   * @return the path that exists, or the module-relative one when neither does
   */
  private static Path resolveModuleRelative(String relative) {
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
