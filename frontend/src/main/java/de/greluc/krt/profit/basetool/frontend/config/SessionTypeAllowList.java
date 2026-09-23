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

package de.greluc.krt.profit.basetool.frontend.config;

import de.greluc.krt.profit.basetool.frontend.metrics.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.DatabindContext;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

/**
 * The closed list of classes a value in the HTTP session may name in its {@code @class} type id,
 * and the switch that decides whether a class outside it is refused (REQ-SEC-067, ADR-0206).
 *
 * <p><strong>What it closes.</strong> The session serializer activates Jackson's default typing, so
 * every non-final session value carries the fully-qualified name of the class it is read back as.
 * Until this list existed the type validator allowed every class ({@code allowIfBaseType(Object)}),
 * which makes Redis a deserialization sink: anybody able to write one field of one session hash
 * could name any class on the frontend's classpath and have Jackson instantiate it and call its
 * setters on the next request carrying that cookie — the shape of every Jackson "gadget" CVE. The
 * justification in the old Javadoc ("session data originates only from our own application") is
 * true of the <em>writer</em> and says nothing about the <em>store</em>, which three services
 * share.
 *
 * <p><strong>What is on it</strong>, each entry matched by name before the class is ever loaded:
 *
 * <ul>
 *   <li>{@code java.util.*} and {@code java.time.*} — the collections, dates and durations Spring
 *       Session, Spring Security and our own filters write. Direct members only: a subpackage such
 *       as {@code java.util.logging} or {@code java.util.concurrent} is <em>not</em> covered;
 *   <li>the boxed scalars of {@code java.lang}, {@code java.math.BigDecimal} / {@code BigInteger}
 *       and {@code java.net.URL} / {@code URI} by exact name. A final type sitting in an {@code
 *       Object}-typed slot of a container is written with a type id after all, as a two-element
 *       array of class name and value — and a real OIDC login puts exactly such values in the
 *       session: the ID token's {@code iss} claim is a {@code URL}, a numeric claim a {@code Long},
 *       and the {@code creationTime} of Spring Session's session-created event payload a {@code
 *       Long} too;
 *   <li>{@code com.nimbusds.jose.shaded.gson.internal.LinkedTreeMap} by exact name — the map Nimbus
 *       decodes a nested JSON claim into ({@code realm_access}, {@code resource_access});
 *   <li>{@code org.springframework.security.*} — the security context, the OAuth2 login and
 *       authorized-client state, the CSRF token and the saved request. The Spring Security Jackson
 *       modules add their own exact types on top, and this prefix covers what they reach through
 *       {@code Object}-typed slots;
 *   <li>{@code org.springframework.web.servlet.FlashMap}, {@code
 *       org.springframework.util.LinkedMultiValueMap} and the direct members of {@code
 *       org.springframework.validation} — a redirect's flash attributes, including the form {@code
 *       BindingResult} a failed validation carries across the redirect;
 *   <li>{@code de.greluc.krt.profit.basetool.frontend.model.*} — the application's own forms and
 *       DTOs, which reach the session as flash attributes;
 *   <li>{@link RedisSessionConfig#CONTAINER_WRITTEN_FINAL_SESSION_TYPES} — the servlet-container
 *       class that already needed a forced type id (ADR-0154).
 * </ul>
 *
 * <p><strong>Three modes</strong>, chosen by {@code app.session.type-allow-list} ({@code
 * APP_SESSION_TYPE_ALLOW_LIST}):
 *
 * <ul>
 *   <li>{@link Mode#OFF} — the validator of before, byte for byte: every class allowed, nothing
 *       counted. The escape hatch if the reporting itself ever misbehaves;
 *   <li>{@link Mode#REPORT} (the default) — every class is still read, exactly as before, and a
 *       class outside the list is counted and named in the log. This is how the list is proven
 *       against production's real sessions before anything is refused;
 *   <li>{@link Mode#ENFORCE} — a class outside the list is refused. The read fails, {@code
 *       FaultTolerantSessionSerializer} drops that one attribute and counts it, and the member
 *       keeps the rest of the session.
 * </ul>
 */
@Slf4j
public final class SessionTypeAllowList {

  /**
   * Direct members of {@code java.util} and {@code java.time}, nested classes included, and nothing
   * from a subpackage. Matched against the whole name, so {@code java.util.concurrent.X} and {@code
   * java.util.logging.FileHandler} do not match.
   */
  static final Pattern JDK_VALUE_TYPES = Pattern.compile("java\\.(?:util|time)\\.[\\w$]+");

  /**
   * The boxed scalars of {@code java.lang}, matched exactly. Not the package: {@code java.lang}
   * also holds {@code ProcessBuilder}, {@code Thread} and {@code ClassLoader}.
   */
  static final Pattern JAVA_LANG_SCALARS =
      Pattern.compile(
          "java\\.lang\\.(?:Boolean|Byte|Character|Double|Float|Integer|Long|Short|String)");

  /**
   * Direct members of {@code org.springframework.validation}: the {@code BindingResult}
   * implementations and the {@code FieldError} / {@code ObjectError} values a failed form carries
   * across a redirect. The {@code beanvalidation} subpackage — factory beans with configuring
   * setters — is deliberately outside it.
   */
  static final Pattern VALIDATION_TYPES =
      Pattern.compile("org\\.springframework\\.validation\\.[\\w$]+");

  /**
   * Name prefixes allowed wholesale: Spring Security, and the application's own session-bound model
   * (forms and DTOs in {@code frontend.model}).
   */
  static final @Unmodifiable List<String> ALLOWED_PREFIXES =
      List.of("org.springframework.security.", "de.greluc.krt.profit.basetool.frontend.model.");

  /**
   * Individually named classes, matched exactly, so a class sharing the prefix (for instance {@code
   * FlashMapManager}) is not allowed by accident: the redirect flash map and the multi-value map it
   * keeps its target parameters in; the arbitrary-precision numbers and the URL / URI a JSON claim
   * or Spring's OIDC claim conversion can produce ({@code iss} becomes a {@code URL}); and the map
   * Nimbus decodes a nested claim object into.
   *
   * <p>{@code java.net.URL} is the one entry with a side effect worth naming: its {@code hashCode}
   * resolves the host, so a {@code URL} placed into a set makes the frontend send a DNS query. A
   * writer able to plant that could already forge any member's security context in the same hash;
   * the query is not the risk this list exists for, and refusing {@code URL} would refuse every
   * signed-in member's ID token.
   */
  static final @Unmodifiable List<String> ALLOWED_EXACT_NAMES =
      List.of(
          "org.springframework.web.servlet.FlashMap",
          "org.springframework.util.LinkedMultiValueMap",
          "java.math.BigDecimal",
          "java.math.BigInteger",
          "java.net.URL",
          "java.net.URI",
          "com.nimbusds.jose.shaded.gson.internal.LinkedTreeMap");

  /**
   * Upper bound on the distinct refused class names that are each logged once at {@code WARN}. A
   * refused type id comes out of the stored payload, so its variety is bounded only by whoever
   * wrote it; past this many names further refusals log at {@code DEBUG} and are still counted.
   */
  private static final int MAX_DISTINCT_WARNINGS = 64;

  /** No instances: the class is a namespace for the list, the modes and the validator factory. */
  private SessionTypeAllowList() {}

  /** How a class outside the allow-list is treated. */
  public enum Mode {
    /** The permissive validator of before; the allow-list is not consulted at all. */
    OFF,
    /** Everything is read as before; a class outside the list is counted and logged. */
    REPORT,
    /** A class outside the list is refused, so the value is dropped as unreadable. */
    ENFORCE;

    /**
     * Parses a configured mode leniently, falling back to {@link #REPORT} on a value it does not
     * recognise.
     *
     * <p>The fallback is the mode that reads exactly what the frontend read before the list
     * existed, so a mistyped flag degrades to "nothing changes" rather than to "sessions start
     * dropping" or "the frontend does not start".
     *
     * @param raw the configured value; case and surrounding whitespace are ignored.
     * @return the matching mode, or {@link #REPORT} when {@code raw} is blank or unrecognised.
     */
    public static @NotNull Mode parse(@Nullable String raw) {
      if (raw == null || raw.isBlank()) {
        return REPORT;
      }
      try {
        return valueOf(raw.strip().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException ex) {
        log.warn(
            "Unrecognised app.session.type-allow-list '{}'; falling back to REPORT."
                + " Valid values: OFF, REPORT, ENFORCE.",
            raw);
        return REPORT;
      }
    }

    /**
     * The value of the {@code mode} tag on {@code basetool_session_type_refused_total}.
     *
     * @return {@code report} or {@code enforce}; {@code off} never reaches the counter.
     */
    @NotNull
    String tag() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** Receives every class the allow-list would refuse, whether or not it was refused. */
  @FunctionalInterface
  interface RefusalListener {

    /** A listener that does nothing, for callers that only need the validator's verdict. */
    RefusalListener NONE = (className, mode) -> {};

    /**
     * Called once per refusal the validator reaches.
     *
     * @param className the fully-qualified name of the class outside the allow-list; a loaded
     *     class's name, never the raw type id.
     * @param mode {@link Mode#REPORT} when the value was read anyway, {@link Mode#ENFORCE} when it
     *     was refused.
     */
    void refused(@NotNull String className, @NotNull Mode mode);
  }

  /**
   * Returns the type-validator builder for the given mode, ready to be handed to {@code
   * SecurityJacksonModules.getModules(loader, builder)}, which adds Spring Security's own exact
   * types to it and activates the default typing with whatever it builds.
   *
   * @param mode the configured mode.
   * @param listener told about each class outside the list; ignored in {@link Mode#OFF}.
   * @return for {@link Mode#OFF} the permissive builder of before ({@code
   *     allowIfBaseType(Object)}); otherwise a builder carrying the allow-list whose {@code
   *     build()} produces the reporting or enforcing validator.
   */
  static BasicPolymorphicTypeValidator.@NotNull Builder validatorBuilder(
      @NotNull Mode mode, @NotNull RefusalListener listener) {
    if (mode == Mode.OFF) {
      return BasicPolymorphicTypeValidator.builder().allowIfBaseType(Object.class);
    }
    AllowListBuilder builder = new AllowListBuilder(mode, listener);
    builder.allowIfSubType(JDK_VALUE_TYPES);
    builder.allowIfSubType(JAVA_LANG_SCALARS);
    builder.allowIfSubType(VALIDATION_TYPES);
    for (String prefix : ALLOWED_PREFIXES) {
      builder.allowIfSubType(prefix);
    }
    for (String exact : ALLOWED_EXACT_NAMES) {
      builder.allowIfSubType(Pattern.compile(Pattern.quote(exact)));
    }
    for (String exact : RedisSessionConfig.CONTAINER_WRITTEN_FINAL_SESSION_TYPES) {
      builder.allowIfSubType(Pattern.compile(Pattern.quote(exact)));
    }
    return builder;
  }

  /**
   * A builder that produces an {@link AllowListValidator} instead of a plain {@link
   * BasicPolymorphicTypeValidator}.
   *
   * <p>A subclass rather than a wrapper around the built validator, because {@code
   * SecurityJacksonModules} calls {@code build()} itself, inside its own module set-up — the
   * builder is the only object this configuration hands over, so it is the only place a different
   * validator can come from.
   */
  static final class AllowListBuilder extends BasicPolymorphicTypeValidator.Builder {

    /** {@link Mode#REPORT} or {@link Mode#ENFORCE}; carried into the validator. */
    private final @NotNull Mode mode;

    /** Carried into the validator. */
    private final @NotNull RefusalListener listener;

    /**
     * Creates an empty builder for the given mode.
     *
     * @param mode {@link Mode#REPORT} or {@link Mode#ENFORCE}.
     * @param listener told about each class outside the list.
     */
    AllowListBuilder(@NotNull Mode mode, @NotNull RefusalListener listener) {
      super();
      this.mode = mode;
      this.listener = listener;
    }

    /**
     * Builds the validator from every matcher added so far — the allow-list's and, by the time
     * {@code SecurityJacksonModules} calls this, Spring Security's.
     *
     * @return an {@link AllowListValidator} over the same matchers the upstream {@code build()}
     *     would have used.
     */
    @Override
    public @NotNull BasicPolymorphicTypeValidator build() {
      return new AllowListValidator(
          _invalidBaseTypes,
          _baseTypeMatchers == null
              ? null
              : _baseTypeMatchers.toArray(new BasicPolymorphicTypeValidator.TypeMatcher[0]),
          _subTypeNameMatchers == null
              ? null
              : _subTypeNameMatchers.toArray(new BasicPolymorphicTypeValidator.NameMatcher[0]),
          _subTypeClassMatchers == null
              ? null
              : _subTypeClassMatchers.toArray(new BasicPolymorphicTypeValidator.TypeMatcher[0]),
          _acceptArrayTypes,
          mode,
          listener);
    }
  }

  /**
   * The upstream validator with one decision changed: what happens to a class none of the matchers
   * allowed.
   *
   * <p>Jackson asks in two steps. {@code validateSubClassName} sees only the name and answers
   * <em>allowed</em> or <em>undecided</em>; for an undecided name Jackson loads the class and asks
   * {@code validateSubType}, where anything but <em>allowed</em> is a refusal. Every entry of the
   * allow-list is a name matcher, so an allowed class is settled in the first step; the second step
   * is where Spring Security's class-based matchers answer, and where everything else ends up. That
   * is the one place this class hooks.
   */
  static final class AllowListValidator extends BasicPolymorphicTypeValidator {

    /** Serial id; the upstream validator is {@link java.io.Serializable}. */
    private static final long serialVersionUID = 1L;

    /** Whether a class outside the list is refused ({@link Mode#ENFORCE}) or only reported. */
    private final @NotNull Mode mode;

    /** Told about each class outside the list. Not serialized; a validator is never persisted. */
    private final transient @NotNull RefusalListener listener;

    /**
     * Creates the validator over the builder's matchers.
     *
     * @param invalidBaseTypes base types never allowed, or {@code null}.
     * @param baseTypeMatchers base-type matchers, or {@code null}.
     * @param subTypeNameMatchers subtype name matchers, or {@code null}.
     * @param subClassMatchers subtype class matchers, or {@code null}.
     * @param acceptArrayTypes whether array types are allowed wholesale.
     * @param mode {@link Mode#REPORT} or {@link Mode#ENFORCE}.
     * @param listener told about each class outside the list.
     */
    AllowListValidator(
        @Nullable Set<Class<?>> invalidBaseTypes,
        BasicPolymorphicTypeValidator.TypeMatcher @Nullable [] baseTypeMatchers,
        BasicPolymorphicTypeValidator.NameMatcher @Nullable [] subTypeNameMatchers,
        BasicPolymorphicTypeValidator.TypeMatcher @Nullable [] subClassMatchers,
        boolean acceptArrayTypes,
        @NotNull Mode mode,
        @NotNull RefusalListener listener) {
      super(
          invalidBaseTypes,
          baseTypeMatchers,
          subTypeNameMatchers,
          subClassMatchers,
          acceptArrayTypes);
      this.mode = mode;
      this.listener = listener;
    }

    /**
     * Returns the upstream verdict for a class the matchers allow; for any other class tells the
     * listener, then refuses it under {@link Mode#ENFORCE} and allows it under {@link Mode#REPORT}.
     *
     * <p>Under {@code REPORT} Jackson caches the deserializer it resolves for a type id, so the
     * same class in the same slot reaches this method once per frontend lifetime, not once per
     * read. The signal is therefore "this class occurs", not "this many values": exactly what
     * deciding to enforce needs. A refusal under {@code ENFORCE} is never cached and is reported on
     * every read.
     *
     * @param ctxt the deserialization context.
     * @param baseType the declared type of the slot being read.
     * @param subType the loaded class the type id named.
     * @return {@code ALLOWED} for a listed class or under {@code REPORT}; the upstream refusal
     *     under {@code ENFORCE}.
     */
    @Override
    public @NotNull Validity validateSubType(
        @NotNull DatabindContext ctxt, @NotNull JavaType baseType, @NotNull JavaType subType) {
      Validity verdict = super.validateSubType(ctxt, baseType, subType);
      if (verdict == Validity.ALLOWED) {
        return verdict;
      }
      listener.refused(subType.getRawClass().getName(), mode);
      return mode == Mode.ENFORCE ? Validity.DENIED : Validity.ALLOWED;
    }
  }

  /**
   * The production listener: counts every refusal on {@code basetool_session_type_refused_total}
   * and names each distinct class once at {@code WARN}.
   *
   * <p>The class name goes into the log, never into a tag. It is a loaded class's name, so it is
   * not member data, but a type id comes out of a stored payload and its variety is set by whoever
   * wrote it — an unbounded label (REQ-OBS-011). The tag is the mode alone.
   */
  @RequiredArgsConstructor
  static final class MeteredRefusalListener implements RefusalListener {

    /**
     * Provider of the registry the counter binds to; an {@link ObjectProvider} for the same reason
     * {@link RedisSessionConfig#springSessionDefaultRedisSerializer(ObjectProvider)} takes one.
     */
    private final @NotNull ObjectProvider<MeterRegistry> meterRegistry;

    /** Class names already logged at {@code WARN}, capped at {@link #MAX_DISTINCT_WARNINGS}. */
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    /**
     * Counts the refusal and, the first time a class is seen, logs it at {@code WARN} with what the
     * mode did to it and what to do about it.
     *
     * @param className the refused class's fully-qualified name.
     * @param mode the mode that was in force.
     */
    @Override
    public void refused(@NotNull String className, @NotNull Mode mode) {
      MeterRegistry registry = meterRegistry.getIfAvailable();
      if (registry != null) {
        registry
            .counter(MetricNames.SESSION_TYPE_REFUSED, MetricNames.TAG_MODE, mode.tag())
            .increment();
      }
      if (firstWarningFor(className)) {
        log.warn(
            "A session value names class {}, which is not on the session type allow-list (mode={})."
                + " {} If the class is legitimately stored in the session, add it to"
                + " SessionTypeAllowList before switching to ENFORCE; otherwise something other"
                + " than this frontend wrote it into Redis. Further occurrences of this class log"
                + " at DEBUG and are counted in basetool_session_type_refused_total.",
            className,
            mode,
            mode == Mode.ENFORCE
                ? "It was refused, so the attribute is dropped and reads as not set."
                : "It was read anyway; ENFORCE would drop it.");
      } else {
        log.debug("Session value of class {} is outside the allow-list (mode={})", className, mode);
      }
    }

    /**
     * Records a class name as warned about, within the cap.
     *
     * @param className the refused class's name.
     * @return {@code true} exactly once per name while fewer than {@link #MAX_DISTINCT_WARNINGS}
     *     names have been recorded; {@code false} afterwards and for every repeat.
     */
    @Contract(mutates = "this")
    private boolean firstWarningFor(@NotNull String className) {
      return warned.size() < MAX_DISTINCT_WARNINGS && warned.add(className);
    }
  }
}
