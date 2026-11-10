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

package de.greluc.krt.profit.basetool.frontend.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.WaitUntilState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Measures optional-asset delivery and script cost with fresh Chromium contexts. */
@Tag("asset-profile")
class FrontendAssetProfileE2eTest {

  @RegisterExtension static final E2eStackExtension STACK = new E2eStackExtension();

  private static final int TRIALS = 5;
  private static final List<String> OPTIONAL_MODULES =
      List.of(
          "krt-user-search",
          "krt-catalog-search",
          "krt-bank-account-search",
          "krt-searchable-select",
          "datetime-splitter",
          "scu-decimal-input",
          "krt-number-input",
          "krt-filter-panel",
          "krt-overflow-menu",
          "krt-filter-chips",
          "krt-list-count",
          "krt-focus");

  @Test
  void recordsColdPayloadAndScriptCostAgainstTheEagerAssetBaseline() throws Exception {
    assertThat(System.getProperty("e2e.browser", "chromium")).isEqualTo("chromium");
    Path output = Path.of(System.getProperty("basetool.resource-profile-output"));
    Files.createDirectories(output);
    String baselineCss =
        minify(
            Files.readString(Path.of(System.getProperty("basetool.asset-profile-baseline-css"))));
    List<Map<String, Object>> eager = new ArrayList<>();
    List<Map<String, Object>> selective = new ArrayList<>();
    try (Playwright playwright = Playwright.create();
        Browser browser = E2eSupport.launchBrowser(playwright, STACK.managesStack())) {
      for (int trial = 0; trial < TRIALS; trial++) {
        eager.add(measure(browser, true, baselineCss, output, trial));
        selective.add(measure(browser, false, baselineCss, output, trial));
      }
    }
    long eagerBytes = ((Number) eager.getFirst().get("decodedAssetBodyBytes")).longValue();
    long selectiveBytes = ((Number) selective.getFirst().get("decodedAssetBodyBytes")).longValue();
    assertThat(selectiveBytes).isLessThan(eagerBytes);
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("page", "/privacy");
    report.put("browser", "chromium");
    report.put("trialsPerVariant", TRIALS);
    report.put(
        "method",
        "Fresh contexts with cache disabled; eager optional tags and pinned original CSS versus"
            + " current selective assets; decoded asset body bytes and Chromium ScriptDuration");
    report.put("eager", eager);
    report.put("selective", selective);
    Files.writeString(
        output.resolve("frontend-assets.json"),
        new GsonBuilder().setPrettyPrinting().create().toJson(report));
  }

  private static Map<String, Object> measure(
      Browser browser, boolean eager, String baselineCss, Path output, int trial) {
    List<Response> assets = new CopyOnWriteArrayList<>();
    List<String> errors = new CopyOnWriteArrayList<>();
    try (BrowserContext context =
        browser.newContext(
            new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setViewportSize(1440, 900))) {
      Page page = context.newPage();
      page.onPageError(errors::add);
      page.onResponse(response -> {
        if (response.url().contains("/js/") || response.url().contains("/css/")) {
          assets.add(response);
        }
      });
      if (eager) {
        page.route(
            "**/privacy",
            route -> {
              var response = route.fetch();
              String html = response.text();
              var nonce = Pattern.compile("nonce=\"([^\"]+)\"").matcher(html);
              assertThat(nonce.find()).isTrue();
              StringBuilder tags = new StringBuilder();
              for (String module : OPTIONAL_MODULES) {
                if (!Pattern.compile(
                        "<script\\b[^>]*src=\"[^\"]*/js/kernel/"
                            + Pattern.quote(module)
                            + "(?:-[A-Za-z0-9]+)?\\.js\"")
                    .matcher(html)
                    .find()) {
                  tags.append("<script type=\"module\" nonce=\"")
                      .append(nonce.group(1))
                      .append("\" src=\"/js/kernel/")
                      .append(module)
                      .append(".js\"></script>");
                }
              }
              Map<String, String> headers = new LinkedHashMap<>(response.headers());
              headers.remove("content-encoding");
              headers.remove("content-length");
              route.fulfill(
                  new Route.FulfillOptions()
                      .setResponse(response)
                      .setHeaders(headers)
                      .setBody(html.replace("</head>", tags + "</head>")));
            });
        page.route(
            "**/css/kernel/styles*.css",
            route ->
                route.fulfill(
                    new Route.FulfillOptions().setContentType("text/css").setBody(baselineCss)));
      }
      CDPSession performance = context.newCDPSession(page);
      performance.send("Network.enable");
      JsonObject disabled = new JsonObject();
      disabled.addProperty("cacheDisabled", true);
      performance.send("Network.setCacheDisabled", disabled);
      performance.send("Performance.enable");
      Response response =
          page.navigate(
              STACK.baseUrl() + "/privacy",
              new Page.NavigateOptions().setWaitUntil(WaitUntilState.LOAD));
      assertThat(response).isNotNull();
      assertThat(response.status()).isEqualTo(200);
      assertThat(errors).isEmpty();
      JsonObject metrics = performance.send("Performance.getMetrics");
      double scriptMillis = 0;
      for (var metric : metrics.getAsJsonArray("metrics")) {
        if (metric.getAsJsonObject().get("name").getAsString().equals("ScriptDuration")) {
          scriptMillis = metric.getAsJsonObject().get("value").getAsDouble() * 1000;
        }
      }
      long scriptBytes = 0;
      long styleBytes = 0;
      for (Response asset : assets) {
        if (asset.url().contains("/js/")) {
          scriptBytes += asset.body().length;
        } else {
          styleBytes += asset.body().length;
        }
      }
      if (trial == 0) {
        page.screenshot(
            new Page.ScreenshotOptions()
                .setFullPage(true)
                .setPath(output.resolve(eager ? "privacy-eager.png" : "privacy-selective.png")));
      }
      return Map.of(
          "decodedAssetBodyBytes",
          scriptBytes + styleBytes,
          "scriptBodyBytes",
          scriptBytes,
          "styleBodyBytes",
          styleBytes,
          "assetRequests",
          assets.size(),
          "scriptDurationMillis",
          scriptMillis);
    }
  }

  private static String minify(String css) {
    return css.replaceAll("(?s)/\\*.*?\\*/", "")
            .lines()
            .map(String::trim)
            .filter(line -> !line.isEmpty())
            .collect(java.util.stream.Collectors.joining("\n"))
        + "\n";
  }
}
