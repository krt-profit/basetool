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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.support.GoldenFile;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Controller;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.context.WebApplicationContext;
import org.thymeleaf.IEngineConfiguration;
import org.thymeleaf.cache.NonCacheableCacheEntryValidity;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ITemplateResolver;
import org.thymeleaf.templateresolver.TemplateResolution;
import org.thymeleaf.templateresource.StringTemplateResource;

/**
 * Pins the page chrome of every page template (REQ-FE-033): the {@code <html>} tag, the head's
 * fixed part and the page's own title and links, the {@code <body>} tag, and the order of the
 * chrome, the page content, the toast and the page tail.
 *
 * <p>Each page is rendered through the real fragments for an administrator, with the page's own
 * markup replaced by markers ({@link ChromeNeutraliser}), so no page needs a model. The golden file
 * is {@value #RESOURCE}; rewrite it with {@code -PupdateSnapshots} when a change of the chrome is
 * intended. The planted tests prove the comparison fails when a page is composed differently.
 */
@SpringBootTest
@Import(PageChromeSnapshotTest.OracleConfig.class)
class PageChromeSnapshotTest {

  /** The golden file below {@code src/test/resources}. */
  static final String RESOURCE = "templates/page-chrome-snapshot.txt";

  /** The view-name prefix the oracle resolver answers. */
  private static final String PREFIX = "chrome-oracle/";

  /** The floor of page templates that carry the chrome, so a vanished scan cannot pass. */
  private static final int PAGE_FLOOR = 90;

  private static final Path TEMPLATE_ROOT = Path.of("src", "main", "resources", "templates");

  @Autowired private WebApplicationContext context;

  @Autowired private OracleResolver resolver;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    resolver.overrides.clear();
  }

  @AfterEach
  void tearDown() {
    resolver.overrides.clear();
  }

  @Test
  void everyPageKeepsItsChrome() throws Exception {
    List<String> pages = pages();
    assertThat(pages.size()).as("pages that carry the chrome").isGreaterThanOrEqualTo(PAGE_FLOOR);
    List<String> lines = new ArrayList<>();
    for (String page : pages) {
      lines.addAll(render(page));
    }
    GoldenFile.assertMatches(RESOURCE, lines, "page chrome");
  }

  @Test
  void aPageWithoutItsHeaderFails() throws Exception {
    assertPlantedChange("admin-settings.html", source -> source.replace(HEADER_CALL, ""));
  }

  @Test
  void aPageWithTheHeaderBeforeTheSidebarFails() throws Exception {
    assertPlantedChange(
        "admin-settings.html",
        source ->
            source.replace(
                SIDEBAR_CALL + "\n\n" + HEADER_CALL, HEADER_CALL + "\n\n" + SIDEBAR_CALL));
  }

  @Test
  void aPageWithoutItsToastFails() throws Exception {
    assertPlantedChange("admin-settings.html", source -> source.replace(TOAST_CALL, ""));
  }

  @Test
  void aPageWithAnExtraBodyAttributeFails() throws Exception {
    assertPlantedChange(
        "admin-settings.html", source -> source.replace("<body>", "<body class=\"planted\">"));
  }

  @Test
  void aPageWithAnotherStylesheetFails() throws Exception {
    assertPlantedChange(
        "admin-settings.html",
        source -> source.replace("/css/pages/admin-settings.css", "/css/pages/planted.css"));
  }

  private static final String SIDEBAR_CALL =
      "<th:block th:replace=\"~{fragments/sidebar :: sidebar}\" />";

  private static final String HEADER_CALL =
      "<th:block th:replace=\"~{fragments/header :: header}\" />";

  private static final String TOAST_CALL =
      "<th:block th:replace=\"~{fragments/toast :: toast}\" />";

  private void assertPlantedChange(
      @NotNull String page, @NotNull java.util.function.UnaryOperator<String> change)
      throws Exception {
    String original =
        Files.readString(TEMPLATE_ROOT.resolve(page), StandardCharsets.UTF_8).replace("\r\n", "\n");
    String planted = change.apply(original);
    assertThat(planted).as("the planted change must alter the source").isNotEqualTo(original);
    resolver.overrides.put(page.substring(0, page.length() - ".html".length()), planted);

    List<String> rendered = render(page);

    assertThat(rendered).as("planted change in " + page).isNotEqualTo(goldenLines(page));
  }

  private @NotNull List<String> goldenLines(@NotNull String page) throws IOException {
    return Files.readAllLines(
            Path.of("src", "test", "resources").resolve(RESOURCE), StandardCharsets.UTF_8)
        .stream()
        .filter(line -> line.startsWith(page + " "))
        .toList();
  }

  private @NotNull List<String> render(@NotNull String page) throws Exception {
    String name = page.substring(0, page.length() - ".html".length());
    var response =
        mockMvc
            .perform(
                get("/chrome-oracle/" + name)
                    .with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                    .header("Accept-Language", "de"))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).as("status of " + page).isEqualTo(200);
    return ChromeSnapshot.lines(page, response.getContentAsString(StandardCharsets.UTF_8));
  }

  private static @NotNull List<String> pages() throws IOException {
    try (Stream<Path> files = Files.walk(TEMPLATE_ROOT)) {
      return files
          .filter(path -> path.toString().endsWith(".html"))
          .map(path -> TEMPLATE_ROOT.relativize(path).toString().replace('\\', '/'))
          .filter(name -> !name.startsWith("fragments/"))
          .filter(PageChromeSnapshotTest::carriesTheChrome)
          .sorted()
          .toList();
    }
  }

  private static boolean carriesTheChrome(@NotNull String name) {
    try {
      String source = Files.readString(TEMPLATE_ROOT.resolve(name), StandardCharsets.UTF_8);
      return source.contains("fragments/head") || source.contains("fragments/layout");
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  /** Registers the oracle resolver and the controller that renders a template by name. */
  @TestConfiguration
  static class OracleConfig {

    @Bean
    OracleResolver oracleResolver() {
      return new OracleResolver();
    }

    @Bean
    OracleController oracleController() {
      return new OracleController();
    }
  }

  /** Renders {@code /chrome-oracle/<template>} as the view of the same name. */
  @Controller
  @UsesLayoutModel
  static class OracleController {

    @GetMapping("/chrome-oracle/**")
    String page(@NotNull HttpServletRequest request) {
      String uri = request.getRequestURI();
      return PREFIX + uri.substring(uri.indexOf(PREFIX) + PREFIX.length());
    }
  }

  /**
   * Serves a page template with its own markup neutralised, from disk or from a planted override.
   */
  static final class OracleResolver implements ITemplateResolver {

    private final Map<String, String> overrides = new ConcurrentHashMap<>();

    @Override
    public String getName() {
      return "chrome-oracle";
    }

    @Override
    public Integer getOrder() {
      return 0;
    }

    @Override
    public TemplateResolution resolveTemplate(
        IEngineConfiguration configuration,
        String ownerTemplate,
        String template,
        Map<String, Object> templateResolutionAttributes) {
      if (!template.startsWith(PREFIX)) {
        return null;
      }
      String name = template.substring(PREFIX.length());
      String source = overrides.get(name);
      if (source == null) {
        try {
          source = Files.readString(TEMPLATE_ROOT.resolve(name + ".html"), StandardCharsets.UTF_8);
        } catch (IOException ex) {
          throw new UncheckedIOException(ex);
        }
      }
      return new TemplateResolution(
          new StringTemplateResource(ChromeNeutraliser.neutralise(source)),
          TemplateMode.HTML,
          NonCacheableCacheEntryValidity.INSTANCE);
    }
  }
}
