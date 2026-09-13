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

package de.greluc.krt.profit.basetool.frontend.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves {@code /manifest.webmanifest}, the document that lets a browser install the Basetool as an
 * app instead of bookmarking it as a page.
 *
 * <p>It exists for iPhone and iPad. Those are the members the tool has no native client for — the
 * Android app cannot follow there, because Apple's Web Distribution (the only channel shaped like
 * that app's GitHub-Releases delivery) requires eligibility bars a fan project cannot clear, so the
 * remaining routes are the App Store or nothing. A home-screen web app is what is left, and it
 * needs exactly this file plus the {@code <head>} tags in {@code fragments/head.html}. The full
 * analysis is {@code docs/APPLE_PLATFORM_FEASIBILITY.md} in the {@code basetool-android}
 * repository; the decision is ADR-0164.
 *
 * <p><strong>A controller rather than a file under {@code static/}.</strong> Three reasons, in
 * descending order of how badly a static file would fail:
 *
 * <ol>
 *   <li><strong>i18n.</strong> {@code name}, {@code short_name} and {@code description} are shown
 *       to the user — under the home-screen icon and in the install dialog. Every user-visible
 *       string in this application comes from the message bundles, with no exceptions, so they are
 *       resolved through {@link MessageSource} for the request's locale rather than frozen into a
 *       JSON file.
 *   <li><strong>Content type.</strong> The registered type is {@code application/manifest+json},
 *       which Spring's static-resource handler does not know: a {@code .webmanifest} file would be
 *       served as {@code application/octet-stream}, and a browser is entitled to ignore it.
 *   <li><strong>No redirect.</strong> The browser fetches this on the landing page, before anyone
 *       has signed in. Behind {@code anyRequest().authenticated()} it would answer {@code 302} into
 *       the OAuth entry point and the manifest would silently be the login page — the identical
 *       trap {@link AssetLinksController} was written for, and the reason its path sits in the same
 *       {@code SecurityConfig} allow-list as this one.
 * </ol>
 *
 * <p><strong>There is deliberately no service worker.</strong> „PWA" normally implies one, and this
 * one ships without: a worker that cached navigations would put member data — bank balances,
 * mission rosters, inventory — into a second store outside every path that wipes the first one,
 * while the backend marks those reads {@code no-store} (REQ-SEC-031) precisely so they are not
 * copied. iOS needs no worker for „Zum Home-Bildschirm", which is the case this ships for. The cost
 * is stated rather than hidden: Chromium browsers require a fetch-handling worker before they offer
 * their own install prompt, so on Android and desktop the app stays installable only through the
 * browser menu. ADR-0164 records that trade.
 */
@RestController
@RequiredArgsConstructor
public class WebAppManifestController {

  /**
   * The {@code Content-Type} for a web app manifest, per the W3C registration.
   *
   * <p>Spelled out rather than taken from {@code MediaType}, which has no constant for it.
   */
  private static final String MANIFEST_JSON = "application/manifest+json";

  /**
   * Splash-screen and install-dialog background: the page background, flat black.
   *
   * <p>Mirrors {@code --color-bg-black} in {@code styles.css} (REQ-UI-003). A mismatch here is
   * visible as a flash of the wrong colour while the app starts.
   */
  private static final String BACKGROUND_COLOR = "#000000";

  /**
   * Browser-UI colour: the page header, not the page background.
   *
   * <p>Mirrors {@code --color-bg-dark-gray}, the {@code header} fill. The status bar sits directly
   * above that header on a phone, so matching the header — not the black body — is what makes the
   * seam disappear.
   */
  private static final String THEME_COLOR = "#141414";

  /** Resolves the three user-visible strings for the request's locale. */
  private final MessageSource messageSource;

  /**
   * Returns the web app manifest for the current locale.
   *
   * <p>Caching is {@code private} with {@code Vary: Cookie} because the body depends on the {@code
   * KRT_LOCALE} cookie: a shared cache would otherwise hand a German manifest to a member who reads
   * the tool in English. One hour is long enough to spare the repeat fetches a browser makes around
   * an install and short enough that a corrected wording reaches installed apps the same day.
   *
   * @return the manifest as {@code application/manifest+json}, always {@code 200} and never a
   *     redirect, for anonymous and authenticated requests alike.
   */
  @GetMapping(path = "/manifest.webmanifest", produces = MANIFEST_JSON)
  public @NotNull ResponseEntity<WebAppManifest> manifest() {
    final Locale locale = LocaleContextHolder.getLocale();
    final var manifest =
        new WebAppManifest(
            "/",
            messageSource.getMessage("pwa.name", null, locale),
            messageSource.getMessage("pwa.short_name", null, locale),
            messageSource.getMessage("pwa.description", null, locale),
            locale.getLanguage(),
            "ltr",
            "/",
            "/",
            "standalone",
            BACKGROUND_COLOR,
            THEME_COLOR,
            List.of(
                new ManifestIcon(
                    "/logos/basetool-appicon-512.png", "512x512", "image/png", "any")));
    return ResponseEntity.ok()
        .header("Cache-Control", "private, max-age=3600")
        .header("Vary", "Cookie")
        .body(manifest);
  }

  /**
   * The manifest document, shaped so Jackson emits the member names the specification requires.
   *
   * <p>Every field is a decision and the non-obvious ones are noted on the parameter. What is
   * deliberately absent: {@code orientation}, because the web UI is responsive and locking it would
   * make a tablet worse; and any {@code shortcuts} or {@code share_target} entry, because both are
   * surfaces the specs do not describe yet.
   *
   * @param id stable identity of the installed app, so a later change to {@code start_url} updates
   *     the existing installation instead of creating a second one beside it.
   * @param name full name, shown in the install dialog.
   * @param shortName short name, shown under the home-screen icon where the full one is truncated.
   * @param description one sentence, shown by the richer install dialogs.
   * @param lang language of the strings above — the request's language, not a fixed one.
   * @param dir writing direction; both shipped locales are left-to-right.
   * @param startUrl what the icon opens: {@code /} answers with the landing page for a visitor and
   *     the dashboard for a member, so one entry point serves both states.
   * @param scope the whole origin, so in-app navigation never bounces out into the browser.
   * @param display {@code standalone} — the app window carries no browser address bar.
   * @param backgroundColor see {@link #BACKGROUND_COLOR}.
   * @param themeColor see {@link #THEME_COLOR}.
   * @param icons the app icon. One entry, the opaque 512 px tile REQ-UI-019 already designates for
   *     the home screen; the transparent favicon glyph is deliberately not offered here, because an
   *     installer that picked it would composite the mark onto an unknown plate.
   */
  public record WebAppManifest(
      @JsonProperty("id") String id,
      @JsonProperty("name") String name,
      @JsonProperty("short_name") String shortName,
      @JsonProperty("description") String description,
      @JsonProperty("lang") String lang,
      @JsonProperty("dir") String dir,
      @JsonProperty("start_url") String startUrl,
      @JsonProperty("scope") String scope,
      @JsonProperty("display") String display,
      @JsonProperty("background_color") String backgroundColor,
      @JsonProperty("theme_color") String themeColor,
      @JsonProperty("icons") List<ManifestIcon> icons) {}

  /**
   * One entry of the manifest's {@code icons} array.
   *
   * @param src absolute path on this origin; {@code /logos/**} is already public and already
   *     excluded from the role-sync and terms gates, so the icon needs no further wiring.
   * @param sizes pixel dimensions, {@code WxH}.
   * @param type media type, so a browser can skip a format it cannot decode without fetching it.
   * @param purpose {@code any} only. {@code maskable} is NOT claimed: Android crops a maskable icon
   *     to its own shape and only the inner ~40 % is guaranteed visible, so declaring it for an
   *     artwork that was not drawn with that safe zone would cut into the mark. A dedicated
   *     maskable asset is a request to the design system, not something to assert here.
   */
  public record ManifestIcon(
      @JsonProperty("src") String src,
      @JsonProperty("sizes") String sizes,
      @JsonProperty("type") String type,
      @JsonProperty("purpose") String purpose) {}
}
