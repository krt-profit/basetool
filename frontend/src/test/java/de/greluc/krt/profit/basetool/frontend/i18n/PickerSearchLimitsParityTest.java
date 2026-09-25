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
 * Tests that a picker relay fetches more rows than the combobox renders (REQ-FE-016), pinning the
 * render caps in the shipped JS and {@code fragments/head.html} against the {@link PickerSearch}
 * constants.
 *
 * <p>Otherwise the overflow hint never shows and a truncated list looks complete.
 */
class PickerSearchLimitsParityTest {

  /** The combobox module carrying the {@code maxResults} default. */
  private static final String COMBOBOX_MODULE = "/static/js/krt-searchable-select.js";

  /** The fragment carrying the per-kind {@code krtComboboxI18n.kinds} overrides. */
  private static final String HEAD_FRAGMENT = "/templates/fragments/head.html";

  /** The mission page module carrying the user autocompletes' render cap. */
  private static final String MISSION_MODULE = "/static/js/mission-detail.js";

  /** Matches the {@code USER_SEARCH_RENDER_CAP} declaration in the mission page module. */
  private static final Pattern MISSION_USER_CAP =
      Pattern.compile("const\\s+USER_SEARCH_RENDER_CAP\\s*=\\s*(\\d+)\\s*;");

  /**
   * Matches the render-cap default in {@code krtSearchableSelect}: the quoted fallback of the
   * {@code opts.maxResults || data.comboboxMax || '<n>'} chain. Anchoring on the whole chain keeps
   * an unrelated quoted number elsewhere in the module from matching.
   */
  private static final Pattern RENDER_CAP_DEFAULT =
      Pattern.compile("opts\\.maxResults\\s*\\|\\|\\s*data\\.comboboxMax\\s*\\|\\|\\s*'(\\d+)'");

  /**
   * Matches the {@code maxResults} override inside the {@code 'remote-locations'} kinds entry,
   * stopping at the next marker key so no later entry's value is picked up.
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
   * Pins the mission page's two user autocompletes to {@link PickerSearch#RENDER_CAP}, below the
   * {@link PickerSearch#PAGE_SIZE} the relay fetches.
   *
   * @throws IOException if the mission module cannot be read from the classpath
   */
  @Test
  void missionUserAutocompleteCap_matchesTheRenderCap() throws IOException {
    assertThat(extract(MISSION_USER_CAP, MISSION_MODULE, "USER_SEARCH_RENDER_CAP"))
        .as("PickerSearch.RENDER_CAP vs USER_SEARCH_RENDER_CAP in %s", MISSION_MODULE)
        .isEqualTo(PickerSearch.RENDER_CAP);
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
   * failing with a readable assertion when the anchor is missing or the number does not fit an int.
   *
   * @param pattern the anchored pattern whose group 1 is the number
   * @param resource the absolute classpath resource path to scan
   * @param what human-readable name of the literal, for the failure message
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
   * Reads a classpath resource as UTF-8 text, failing the test when it is missing.
   *
   * @param resource the absolute classpath resource path
   * @return the resource content
   * @throws IOException if the resource stream cannot be read
   */
  private static String readResource(String resource) throws IOException {
    try (InputStream in = PickerSearchLimitsParityTest.class.getResourceAsStream(resource)) {
      assertThat(in).as("classpath resource %s", resource).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
