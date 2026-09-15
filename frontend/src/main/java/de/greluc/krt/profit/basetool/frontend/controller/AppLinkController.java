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

import de.greluc.krt.profit.basetool.frontend.config.UsesLayoutModel;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.view.RedirectView;

/**
 * The web-side fallback for the Android app's App Link, {@code /app/callback} (REQ-SEC-038).
 *
 * <p>That path is the app's OAuth2 redirect URI <em>and</em> its post-logout redirect, claimed by
 * the app through an {@code autoVerify} intent filter. When the claim holds, Android hands the URL
 * to the app and this controller is never reached. When it does not, the browser follows the link
 * instead — and until this existed the frontend had no such route, so the member landed on the
 * generic 404 page <strong>in the middle of signing in</strong>, with a live authorization code
 * still in the address bar.
 *
 * <p><strong>Serving a correct {@code assetlinks.json} does not remove that case.</strong>
 * REQ-SEC-038 originally read as though the 404 were purely a symptom of that file answering {@code
 * 302}, which was fixed on 2026-09-13. Two ways in survive it, and neither can be closed from the
 * server:
 *
 * <ul>
 *   <li>A device whose domain verification already failed <em>keeps</em> that state. On Android 12+
 *       it is sticky, and the member has to re-enable the link by hand — so every phone that
 *       installed the app while the file still redirected is still in that state.
 *   <li>A desktop browser has no app to hand the link to at all.
 * </ul>
 *
 * <p>Both were observed in a single 24-hour window on 2026-09-15, which is what turned the fallback
 * from a nicety into a requirement.
 *
 * <p><strong>Deliberately NOT added to {@code PublicPaths}</strong>, so both paths stay behind the
 * pending-approval and consent gates. The precedent is {@code "/"}, which is {@code permitAll} and
 * still gate-eligible because it is a <em>page</em> — unlike the public documents, which are
 * machine-fetched and were a real cost on every hit. Here the gates are also the better answer: a
 * member who owes consent, or who is awaiting approval, is told that rather than how to repair an
 * App Link, and their login could not have completed anyway. Anonymous callers — the common case on
 * this path — pay nothing either way, since {@code BackendRoleSyncFilter} only acts on an
 * authenticated {@code OAuth2AuthenticationToken}.
 */
@Controller
@UsesLayoutModel
public class AppLinkController {

  /** Where {@link #callback()} sends the browser, and the view that page renders. */
  private static final String HELP_PATH = "/app/link-help";

  /**
   * Answers the App Link with an immediate redirect to {@link #HELP_PATH}, dropping the query.
   *
   * <p><strong>The redirect is the point, not the destination.</strong> The URL that reaches this
   * handler carries a live OAuth2 authorization {@code code}. Rendering a page here would leave
   * that code in the address bar, in the browser's history entry, and in the {@code Referer} of
   * every subresource the page pulls — the response is {@code Referrer-Policy:
   * strict-origin-when-cross-origin}, so same-origin subresources receive the full query. A
   * redirect leaves none of that: the intermediate URL does not become a history entry, and the
   * page the member ends up on has a clean address.
   *
   * <p>The code itself is single-use and PKCE-bound, so what is prevented here is exposure rather
   * than an exploit — which is exactly the reasoning RFC 9700 applies to codes in URLs.
   *
   * <p>{@code 303} rather than {@code 302}: the response to this GET is a different resource, which
   * is what See Other means, and it keeps the redirect out of any method-rewrite ambiguity.
   *
   * @return a {@code 303} redirect to the help page, with no query string attached
   */
  @GetMapping("/app/callback")
  public RedirectView callback() {
    RedirectView redirect = new RedirectView(HELP_PATH);
    redirect.setStatusCode(HttpStatus.SEE_OTHER);
    // Model attributes would be appended to the target as query parameters. There are none today,
    // and a layout advice adding one later must not be able to put it in the URL.
    redirect.setExposeModelAttributes(false);
    return redirect;
  }

  /**
   * Renders the page that explains why the login ended up in the browser and how to repair it.
   *
   * <p>Anonymous by design: the member arriving here is mid-login and, on a device whose App Link
   * never resolved, may have no web session at all. Behind the authenticated catch-all this page
   * would redirect into the OAuth2 entry point, which is the very loop it exists to break.
   *
   * <p>No {@code Model} parameter: the template is static, and the layout attributes come from the
   * {@link UsesLayoutModel} advices, which populate the model whether or not a handler asks for it.
   *
   * @return the {@code app-link-help} view name
   */
  @GetMapping(HELP_PATH)
  public String linkHelp() {
    return "app-link-help";
  }
}
