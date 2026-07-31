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
import static org.assertj.core.api.Assertions.fail;

import de.greluc.krt.profit.basetool.frontend.support.PickerSearch;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Build-time enforcement of the REQ-FE-016 "a picker relay fetches more than the combobox renders"
 * rule, whose two halves live on opposite sides of the wire.
 *
 * <p>The render cap is a browser-side value ({@code krt-searchable-select.js}'s {@code maxResults}
 * default, plus the per-kind override in {@code fragments/head.html}); the page size is a
 * server-side one ({@link PickerSearch}). Nothing at compile time relates them, and when they drift
 * the failure is <b>completely silent</b>: the component gates its "keep typing to narrow the list"
 * hint on {@code matches.length > maxResults}, so a relay fetching the render cap or fewer rows can
 * never trip it. The user then sees a list that looks complete and is not. That is exactly how 28
 * of the 53 visible locations — MIC-L5, Patch City, New Babbage, Orison among them — became
 * unbookable in the Lager Einbuchen picker: a 25-row relay against a 50-row cap.
 *
 * <p>This test reads the shipped JS and the head fragment off the test-runtime classpath ({@code
 * src/main/resources} is on it), extracts the two literals, and pins them against the Java
 * constants — the same technique as {@code ComboboxKindsParityTest} and {@code
 * LiveSyncSectionMapParityTest}.
 */
class PickerSearchLimitsParityTest {

  /** The combobox module carrying the {@code maxResults} default. */
  private static final String COMBOBOX_MODULE = "/static/js/krt-searchable-select.js";

  /** The fragment carrying the per-kind {@code krtComboboxI18n.kinds} overrides. */
  private static final String HEAD_FRAGMENT = "/templates/fragments/head.html";

  /**
   * Matches the render-cap default in {@code krtSearchableSelect}: the quoted fallback of the
   * {@code opts.maxResults || data.comboboxMax || '<n>'} chain. Anchoring on the whole chain keeps
   * an unrelated quoted number elsewhere in the module from matching.
   */
  private static final Pattern RENDER_CAP_DEFAULT =
      Pattern.compile("opts\\.maxResults\\s*\\|\\|\\s*data\\.comboboxMax\\s*\\|\\|\\s*'(\\d+)'");

  /**
   * Matches the {@code maxResults} override inside the {@code 'remote-locations'} kinds entry. The
   * lookahead stops the (lazy, DOTALL) scan at the next marker key, so the value can only come from
   * this entry and never from a later kind's. A brace-excluding class would be wrong here: the
   * property values are Thymeleaf inline expressions ({@code /*[[#{…}]]* /}) and carry literal
   * braces of their own.
   */
  private static final Pattern LOCATION_KIND_MAX =
      Pattern.compile(
          "'remote-locations':\\s*\\{(?:(?!'[\\w-]+':).)*?maxResults:\\s*(\\d+)", Pattern.DOTALL);

  /**
   * Pins {@link PickerSearch#RENDER_CAP} to the {@code maxResults} default the shipped combobox
   * actually uses, so the generic relays keep fetching strictly more rows than are rendered.
   *
   * @throws IOException if the combobox module cannot be read from the classpath
   */
  @Test
  void renderCapConstant_matchesTheShippedComboboxDefault() throws IOException {
    assertThat(extract(RENDER_CAP_DEFAULT, COMBOBOX_MODULE, "maxResults default"))
        .as("PickerSearch.RENDER_CAP vs the maxResults default in %s", COMBOBOX_MODULE)
        .isEqualTo(PickerSearch.RENDER_CAP);
  }

  /**
   * Pins {@link PickerSearch#LOCATION_RENDER_CAP} to the {@code remote-locations} kind's {@code
   * maxResults} override, so the location relay keeps fetching past whatever the picker renders.
   *
   * @throws IOException if the head fragment cannot be read from the classpath
   */
  @Test
  void locationRenderCapConstant_matchesTheKindsOverride() throws IOException {
    assertThat(extract(LOCATION_KIND_MAX, HEAD_FRAGMENT, "remote-locations maxResults"))
        .as("PickerSearch.LOCATION_RENDER_CAP vs the kinds override in %s", HEAD_FRAGMENT)
        .isEqualTo(PickerSearch.LOCATION_RENDER_CAP);
  }

  /**
   * The invariant the two constants exist for: every page size must exceed the render cap it
   * serves. Equality is not enough — {@code matches.length > maxResults} is a strict comparison, so
   * a page ending exactly at the cap leaves the hint unreachable and the cap silent.
   */
  @Test
  void everyPageSize_exceedsTheRenderCapItServes() {
    assertThat(PickerSearch.PAGE_SIZE)
        .as("generic picker page size must exceed the render cap, or the overflow is undetectable")
        .isGreaterThan(PickerSearch.RENDER_CAP);
    assertThat(PickerSearch.LOCATION_PAGE_SIZE)
        .as("location page size must exceed the location render cap for the same reason")
        .isGreaterThan(PickerSearch.LOCATION_RENDER_CAP);
  }

  /**
   * Extracts the single capture group of {@code pattern} from a classpath resource as an int,
   * failing loudly when the anchor is gone (a refactor that renames the chain must break this gate,
   * not silently skip it).
   *
   * <p>The patterns capture {@code \d+}, so the group can only ever be digits — but not necessarily
   * digits that fit an {@code int}. A cap literal long enough to overflow would otherwise surface
   * as a bare {@link NumberFormatException} stack trace naming neither the file nor the value;
   * translating it into an assertion keeps a nonsense edit as readable as a drifted one, which is
   * the entire point of this gate.
   *
   * @param pattern the anchored pattern whose group 1 is the number
   * @param resource the absolute classpath resource path to scan
   * @param what human-readable name of the literal, used in the failure message
   * @return the matched number
   * @throws IOException if the resource stream cannot be read
   */
  private static int extract(Pattern pattern, String resource, String what) throws IOException {
    Matcher matcher = pattern.matcher(readResource(resource));
    assertThat(matcher.find()).as("%s not found in %s (anchor renamed?)", what, resource).isTrue();
    String digits = matcher.group(1);
    try {
      return Integer.parseInt(digits);
    } catch (NumberFormatException e) {
      return fail("%s in %s is not a usable int: '%s'".formatted(what, resource, digits), e);
    }
  }

  /**
   * Reads a classpath resource as UTF-8 text, failing the test if it is missing (a moved or renamed
   * asset must break this gate loudly rather than silently empty the scanned text).
   *
   * @param resource the absolute classpath resource path
   * @return the resource content as a UTF-8 string
   * @throws IOException if the resource stream cannot be read
   */
  private static String readResource(String resource) throws IOException {
    try (InputStream in = PickerSearchLimitsParityTest.class.getResourceAsStream(resource)) {
      assertThat(in).as("classpath resource %s", resource).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
