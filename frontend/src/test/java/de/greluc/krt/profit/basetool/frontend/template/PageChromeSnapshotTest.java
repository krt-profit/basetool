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
import java.util.function.UnaryOperator;
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
 * intended. The planted tests prove the comparison fails when a page, or the layout fragment, is
 * composed differently.
 */
@SpringBootTest(properties = "spring.thymeleaf.cache=false")
@Import(PageChromeSnapshotTest.OracleConfig.class)
class PageChromeSnapshotTest {

  /** The golden file below {@code src/test/resources}. */
  static final String RESOURCE = "templates/page-chrome-snapshot.txt";

  /** The view-name prefix the oracle resolver answers. */
  private static final String PREFIX = "chrome-oracle/";

  /** The layout fragment's template name, which the planted tests replace. */
  private static final String LAYOUT = "kernel/fragments/layout";

  /** The floor of page templates that carry the chrome, so a vanished scan cannot pass. */
  private static final int PAGE_FLOOR = 90;

  /** A page with links, a toast and a tail, which the page-level planted changes edit. */
  private static final String FULL_PAGE = "settings/admin-settings.html";

  /** Pages the layout-level planted changes render: full chrome, no toast, and the bare shell. */
  private static final List<String> LAYOUT_PROBES =
      List.of(FULL_PAGE, "shell/sc-links.html", "error.html");

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
  void aPageThatDropsItsToastFails() throws Exception {
    assertPlantedPageChange(source -> source.replace(", pageToast=true", ""));
  }

  @Test
  void aPageThatDropsItsTailFails() throws Exception {
    assertPlantedPageChange(source -> source.replace(", pageTail=~{::pageTail}", ""));
  }

  @Test
  void aPageThatDropsItsLinksFails() throws Exception {
    assertPlantedPageChange(source -> source.replace(", pageLinks=~{::extraLinks}", ""));
  }

  @Test
  void aPageThatTurnsTheChromeOffFails() throws Exception {
    assertPlantedPageChange(
        source -> source.replace("pageTitle=~{::title}", "pageTitle=~{::title}, pageBare=true"));
  }

  @Test
  void aPageThatAddsABodyAttributeFails() throws Exception {
    assertPlantedPageChange(
        source -> source.replace(", pageToast=true", ", pageToast=true, pageShowModal=true"));
  }

  @Test
  void aPageWithAnotherStylesheetFails() throws Exception {
    assertPlantedPageChange(
        source ->
            source.replace("/css/pages/settings/admin-settings.css", "/css/pages/planted.css"));
  }

  @Test
  void aLayoutWithTheHeaderBeforeTheSidebarFails() throws Exception {
    assertPlantedLayoutChange(
        layout -> {
          String sidebar = "<th:block th:replace=\"~{kernel/fragments/sidebar :: sidebar}\" />";
          String header = "<th:block th:replace=\"~{kernel/fragments/header :: header}\" />";
          return layout.replace(sidebar, "@@").replace(header, sidebar).replace("@@", header);
        });
  }

  @Test
  void aLayoutWithTheContentAfterTheToastFails() throws Exception {
    assertPlantedLayoutChange(
        layout -> {
          String content = "<th:block th:replace=\"${pageContent}\"></th:block>";
          return layout.replace(content, "").replace("</body>", content + "\n</body>");
        });
  }

  @Test
  void aLayoutThatIgnoresTheBareShellFails() throws Exception {
    assertPlantedLayoutChange(
        layout -> layout.replace("th:unless=\"${pageBare}\"", "th:if=\"true\""));
  }

  private void assertPlantedPageChange(@NotNull UnaryOperator<String> change) throws Exception {
    String original = source(FULL_PAGE);
    String planted = change.apply(original);
    assertThat(planted).as("the planted change must alter the source").isNotEqualTo(original);
    resolver.overrides.put(FULL_PAGE.substring(0, FULL_PAGE.length() - ".html".length()), planted);

    assertThat(render(FULL_PAGE))
        .as("planted change in " + FULL_PAGE)
        .isNotEqualTo(goldenLines(FULL_PAGE));
  }

  private void assertPlantedLayoutChange(@NotNull UnaryOperator<String> change) throws Exception {
    String original = source(LAYOUT + ".html");
    String planted = change.apply(original);
    assertThat(planted).as("the planted change must alter the layout").isNotEqualTo(original);
    resolver.overrides.put(LAYOUT, planted);

    List<String> rendered = new ArrayList<>();
    List<String> golden = new ArrayList<>();
    for (String page : LAYOUT_PROBES) {
      rendered.addAll(render(page));
      golden.addAll(goldenLines(page));
    }
    assertThat(rendered).as("planted change in the layout").isNotEqualTo(golden);
  }

  private static @NotNull String source(@NotNull String name) throws IOException {
    return Files.readString(TEMPLATE_ROOT.resolve(name), StandardCharsets.UTF_8)
        .replace("\r\n", "\n");
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
          .filter(name -> !name.contains("fragments/"))
          .filter(PageChromeSnapshotTest::carriesTheChrome)
          .sorted()
          .toList();
    }
  }

  private static boolean carriesTheChrome(@NotNull String name) {
    try {
      String source = Files.readString(TEMPLATE_ROOT.resolve(name), StandardCharsets.UTF_8);
      return source.contains("kernel/fragments/head") || source.contains("kernel/fragments/layout");
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
   * Serves a page template with its own markup neutralised, from disk or from a planted override,
   * and the layout fragment from a planted override while one is set.
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
      if (LAYOUT.equals(template) && overrides.containsKey(LAYOUT)) {
        return resolution(overrides.get(LAYOUT));
      }
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
      return resolution(ChromeNeutraliser.neutralise(source));
    }

    private static TemplateResolution resolution(String source) {
      return new TemplateResolution(
          new StringTemplateResource(source),
          TemplateMode.HTML,
          NonCacheableCacheEntryValidity.INSTANCE);
    }
  }
}
