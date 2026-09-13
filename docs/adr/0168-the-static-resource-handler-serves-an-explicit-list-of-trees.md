# ADR-0168 — The static resource handler serves an explicit list of trees, not everything

- **Status:** Accepted — implemented
- **Date:** 2026-09-13
- **Deciders:** Claude (measurement and implementation), @greluc (ratification pending)
- **Related:** `GlobalExceptionHandler` (the 404 mapping this made load-bearing) ·
  `StaticResourceHandlerMappingTest` (the guard)

> **Numbering:** 0164–0167 are already claimed on other in-flight branches, so this one takes 0168.
> Worth knowing: the sequence has collided before — 0154 and 0165 each name two different ADRs on
> different branches — because the number is picked from whatever the branch can see.

## Context

`WebMvcConfig` registered one resource handler on `/**` with all four of Spring Boot's default
locations:

```java
registry.addResourceHandler("/**")
    .addResourceLocations("classpath:/META-INF/resources/", "classpath:/resources/",
                          "classpath:/static/", "classpath:/public/")
```

`spring.web.resources.chain.strategy.content.enabled: true` satisfies
`@ConditionalOnEnabledResourceChain`, which registers `ResourceUrlEncodingFilter`. That filter wraps
the response so **every URL a Thymeleaf `@{...}` expression emits** goes through
`ResourceUrlProvider.getForLookupPath`, which walks the resolver chain of every registered handler
whose pattern matches the lookup path. With the pattern at `/**`, that is every URL in the
application — including the ones that are controller routes and can never be a file.

`CachingResourceResolver` caches only non-`null` results. A lookup that resolves to nothing is
therefore never remembered, and the same four classpath locations are probed again on the next
render, and the next.

**The cost was measured rather than argued.** Resolving six controller routes through
`ResourceUrlProvider`, 20 000 times each, in the `test` profile context:

|        Configuration         | Per controller-route lookup |
|------------------------------|-----------------------------|
| Catch-all present (before)   | **267 µs**                  |
| Narrow patterns only (after) | **0.25 µs**                 |

The absolute figure depends on the shape of the classpath and is not a production number. Two things
in it are durable and are the actual finding: the ratio is ~1000×, and **20 000 repeats of the same
six paths never got cheaper** — which is the missing cache, visible directly. For scale, the shared
chrome fragments emit **77** controller-route `@{}` links, on every page, before a page's own
content adds its share; across all templates there are 421.

Two further facts were measured because both decide the shape of the fix:

- **What is actually on the classpath.** Enumerating `classpath*:` under all four configured
  locations inside a running context yields `images` and `logos` under `META-INF/resources/`, and
  `css`, `fonts`, `images`, `js` and `robots.txt` under `static/`. `classpath:/resources/` and
  `classpath:/public/` do not exist, and **no dependency JAR contributes anything** under any of
  them — so two of the four locations, and the auto-configured `/webjars/**` mapping, were dead
  weight.
- **Whether narrowing alone is enough. It is not.** Spring Boot's auto-configuration registers `/**`
  (and `/webjars/**`) into the *same* registry. Today that is invisible because our `/**` overwrites
  Boot's under the same map key; the moment our patterns become narrow, Boot's catch-all survives
  beside them and keeps matching everything. Narrowing without
  `spring.web.resources.add-mappings: false` would have been a no-op — and measurably is one: the
  "before" row above was produced by re-enabling exactly that flag.

## Decision

**The handler serves one explicit registration per asset tree that exists, and Boot's default
mappings are off.** Each pattern's locations point into its own tree, because
`extractPathWithinPattern` hands the handler only the part of the path the wildcard matched:
`/css/**` resolves `/css/styles.css` as `styles.css`, so the location must be
`classpath:/static/css/`, not `classpath:/static/`.

|    Pattern    |                              Locations                               |
|---------------|----------------------------------------------------------------------|
| `/css/**`     | `classpath:/static/css/`                                             |
| `/fonts/**`   | `classpath:/static/fonts/`                                           |
| `/images/**`  | `classpath:/META-INF/resources/images/`, `classpath:/static/images/` |
| `/js/**`      | `classpath:/static/js/`                                              |
| `/logos/**`   | `classpath:/META-INF/resources/logos/`                               |
| `/robots.txt` | `classpath:/static/`                                                 |

The cache headers and the resource chain are unchanged and spelled once, in a private helper: a tree
that silently lost its `VersionResourceResolver` would be served without a content hash **and**
cached for a year as `immutable`, which is unrecoverable for that URL.

`StaticResourceHandlerMappingTest` pins the set by **deriving it from the classpath** rather than
from a second hand-written list, so adding `static/audio/` without a handler fails a test instead of
404ing in production, and a reintroduced catch-all fails it too.

## Alternatives rejected

- **Leave it.** A per-render cost that grows with every template link, on the page-render path, for
  lookups that are structurally guaranteed to fail.
- **Emit literal URLs for the links that miss.** Fixes one link at a time, loses the content hash
  where the target is an asset, and hard-codes away the context-path handling `@{...}` provides.
- **One pattern per tree sharing the original four locations.** Does not work: the matched prefix is
  stripped before the location is consulted, so `/css/styles.css` would be looked for at
  `classpath:/static/styles.css`. Found by testing, after reasoning had suggested otherwise.
- **A regex pattern such as `/{tree:css|js|images|…}/**` against the unchanged locations.** It does
  keep the prefix — but only under `PathPatternParser`. `AntPathMatcher` extracts a different string
  from the same pattern, so the configuration would silently mean two things depending on a setting
  nobody would think to check.
- **Registering `/favicon.ico` and `/sm/**` too**, both `permitAll` in `SecurityConfig`. Nothing
  ships a file at either: the favicon is declared by `<link rel="icon">` against `/logos/`, and
  `/sm/` is a path third-party browser extensions probe (Sentry Replay), allow-listed so those
  probes never reach the OAuth entry point — not because the app serves it. Both answered 404
  before and answer 404 now.

## Consequences

- **`NoHandlerFoundException` became reachable for the first time, and cost a 500 before it was
  mapped.** While the handler was a catch-all it matched every unmapped URL, so the dispatcher always
  found a handler and *every* 404 in the application arrived at `GlobalExceptionHandler` as a
  `NoResourceFoundException`. Narrowing the patterns — plus `add-mappings: false`, which is what lets
  Spring raise the dispatcher's own exception at all — routed unmatched paths to the `Exception`
  catch-all instead, and `/favicon.ico` and `/actuator/health` on the public connector went from 404
  to **500**. `ManagementPortIsolationTest` caught it. Both exceptions now map to the 404 page, and
  `StaticResourceHandlerMappingTest` exercises both shapes.
- **The `spring.web.resources.cache.cachecontrol` block is retired.** It only ever reached Boot's own
  registrations; `WebMvcConfig` sets `immutable` on every tree itself, and leaving the weaker
  `must-revalidate` in the file suggested a header the app does not send.
- **`spring.web.resources.chain.strategy.content.enabled` is now load-bearing only for the filter.**
  `WebMvcConfig` builds the chain itself, so the key looks inert — but
  `@ConditionalOnEnabledResourceChain` reads it to register `ResourceUrlEncodingFilter`, and
  switching it off would silently stop the content hashing app-wide. Said so in the file.
- **`/robots.txt` no longer resolves to a content-hashed URL** (an exact pattern leaves no wildcard
  for the version strategy). It is served at its literal path, which is the only path a crawler
  fetches, and no template links to it.
- **Adding an asset tree is now a two-file change** — the directory, and a line in `WebMvcConfig`.
  That is the price of the guarantee, and the test names it rather than leaving it to be discovered
  as a 404.

