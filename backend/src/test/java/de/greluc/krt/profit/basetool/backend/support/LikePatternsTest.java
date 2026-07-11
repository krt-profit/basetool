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

package de.greluc.krt.profit.basetool.backend.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LikePatterns}: the LIKE metacharacters {@code %} and {@code _} and the
 * backslash escape character are neutralised, the backslash is escaped first (no double-escaping),
 * a plain term is unchanged, and the nullable variant preserves {@code null}.
 */
class LikePatternsTest {

  @Test
  void escape_neutralisesWildcardsAndBackslash_backslashFirst() {
    assertThat(LikePatterns.escape("50%")).isEqualTo("50\\%");
    assertThat(LikePatterns.escape("a_b")).isEqualTo("a\\_b");
    // A backslash is doubled, not left able to escape the following char.
    assertThat(LikePatterns.escape("a\\b")).isEqualTo("a\\\\b");
    // Combined: backslash first, then % and _ each get a single leading backslash.
    assertThat(LikePatterns.escape("a%b_c\\d")).isEqualTo("a\\%b\\_c\\\\d");
  }

  @Test
  void escape_leavesAPlainTermUnchanged() {
    assertThat(LikePatterns.escape("Titanium")).isEqualTo("Titanium");
    assertThat(LikePatterns.escape("")).isEqualTo("");
  }

  @Test
  void contains_wrapsTheEscapedFragmentInPercents() {
    assertThat(LikePatterns.contains("a%b")).isEqualTo("%a\\%b%");
    assertThat(LikePatterns.contains("plain")).isEqualTo("%plain%");
  }

  @Test
  void escapeNullable_preservesNull_andEscapesOtherwise() {
    assertThat(LikePatterns.escapeNullable(null)).isNull();
    assertThat(LikePatterns.escapeNullable("a_b")).isEqualTo("a\\_b");
  }
}
