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

package de.greluc.krt.profit.basetool.frontend.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Build-time check that the {@code .krt-segmented} toggle sizes its segments to their labels when
 * it is rendered as a {@code <nav>}, whatever the header's bare {@code nav} rules set (REQ-UI-028).
 */
class SegmentedControlLayoutTest {

  private static final Pattern BLOCK = Pattern.compile("(?m)^\\s*\\.krt-segmented\\s*\\{([^}]*)}");

  /**
   * Asserts that the component rule resets the gap and the flex sizing a bare {@code nav} rule
   * would otherwise give the toggle.
   *
   * @throws IOException if the stylesheet cannot be read
   * @throws URISyntaxException if the classpath root cannot be resolved
   */
  @Test
  void theToggleOverridesTheBareNavLayout() throws IOException, URISyntaxException {
    String css =
        CssSources.stripComments(
            Files.readString(
                CssSources.mainResources().resolve("static/css/kernel/styles.css"),
                StandardCharsets.UTF_8));
    Matcher block = BLOCK.matcher(css);
    assertThat(block.find()).as(".krt-segmented rule in styles.css").isTrue();
    String body = block.group(1);
    assertThat(body).as("REQ-UI-028: no gap between segments").containsPattern("\\bgap:\\s*0;");
    assertThat(body)
        .as("REQ-UI-028: the toggle does not grow in a flex row")
        .containsPattern("\\bflex:\\s*0 1 auto;");
  }
}
