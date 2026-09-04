# RCDoors Build Environment: Blockers to `spigot-core`

**Status as of `claude/new-session-zd9g9u`.** Supersedes the egress section of the
earlier wizard handoff, which listed the wrong hosts.

`animatedarchitecture-core` builds and tests cleanly (402 tests, all passing).
`animatedarchitecture-spigot/spigot-core` does not build in a clean environment.
Two separate problems cause this. Only one of them is a network policy issue.

---

## 1. Two hosts still need egress allowlisting

Both are denied at the CONNECT layer by organization policy, not by the
destination:

```
repo.minebench.de
repo.glaremasters.me
```

| Host | Artifacts | Blocks |
|---|---|---|
| `repo.minebench.de` | `de.themoep:inventorygui:1.6.5-SNAPSHOT` | `spigot-core` |
| `repo.glaremasters.me` | `com.griefdefender:api:2.1.0-SNAPSHOT` | `hook-griefdefender-2`, and therefore `hooks-bundle` and `spigot-core` |

`repo.minebench.de` is the one that matters most. `inventorygui` is the library
every GUI class in `spigot-core` is built on, so no inventory or wizard work can
be compiled without it. It is not published to Maven Central, and JitPack only
offers an unpinned `master-SNAPSHOT` of the upstream GitHub project. Swapping the
coordinate is not a safe workaround for a plugin RCHeists loads in production.

Declared in:

- `animatedarchitecture-spigot/spigot-core/pom.xml` (`minebench-repo`)
- `animatedarchitecture-spigot/protection-hooks/hook-griefdefender-2/pom.xml`

### Already fixed, do not re-request

These were blocked previously and now resolve:

```
maven.enginehub.org
s01.oss.sonatype.org
```

With those open, `hook-plotsquared-6`, `hook-plotsquared-7`,
`hook-griefprevention`, `hook-towny`, `hook-world-guard-7`, `hook-redprotect`,
and `hook-lands` all compile. GriefDefender is the only hook still failing.

### Probably safe to delete rather than allowlist

`oss.sonatype.org` is declared in the root `pom.xml` (line 92) and is also
blocked. Nothing in the reachable reactor resolves from it, and Sonatype has
retired OSSRH. Removing the declaration is likely cleaner than allowlisting a
dead host, but that should be confirmed once `spigot-core` builds end to end.

---

## 2. `rcplatform-api` has no resolvable source

This is not a network policy problem and allowlisting will not fix it.

`animatedarchitecture-spigot/spigot-core/pom.xml` line 34 declares:

```xml
<groupId>net.republicraft.platform</groupId>
<artifactId>rcplatform-api</artifactId>
<version>1.0.0</version>
<scope>provided</scope>
```

**No `<repository>` anywhere in the project says where this artifact comes from.**
Maven therefore falls back to trying every declared repository in turn and fails
on all of them. The local cache holds only a `.lastUpdated` failure marker, no
POM and no JAR.

It is not an optional dependency. `AnimatedArchitecturePlugin.java` imports
`net.republicraft.platform.api.capability` and
`net.republicraft.platform.api.diagnostics` in main source, and
`RCPlatformDoorServiceTest` depends on it in tests. It arrived in commit
`2d7eef4` ("feat: publish RCPlatform door service").

This means `spigot-core` currently builds only on a machine where someone has
manually run `mvn install` on the RCPlatform project. Any fresh clone, any CI
runner, and any new contributor hits this wall.

**Pick one before the Spigot layer can be considered reproducible:**

1. Publish `rcplatform-api` to a repository and declare it in the pom. Preferred.
   A private GitHub Packages or GitLab Maven registry is enough, and that host
   then also needs allowlisting.
2. Vendor the API into the repo as a module, if the surface is small.
3. Commit the JAR and declare it with a `system`-scoped path. Works, but is the
   worst option and Maven has deprecated it.

---

## 3. Verifying a fix

Requires JDK 25. The container default is JDK 21 and fails with
`error: release version 25 not supported`.

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
mvn -B -pl animatedarchitecture-spigot/spigot-core -am -DskipTests compile
```

Read the log rather than trusting an exit code from a wrapper. Grep for
`BUILD SUCCESS`, and check that `spigot-core` shows `SUCCESS` rather than
`SKIPPED`. A skipped module is not a passing module.

Both problems in sections 1 and 2 must be resolved for this to pass. Fixing only
the egress policy moves the failure from `hooks-bundle` to `spigot-core`, it does
not clear it.

---

## 4. How this was established

The previous handoff derived its host list by reading POM files, which is why it
named two hosts that were already fine and missed the two that matter. This list
was produced by testing instead:

1. Probed every declared repository host directly and separated proxy CONNECT
   denials from ordinary HTTP responses.
2. Temporarily removed `hook-griefdefender-2` from the reactor and stubbed its
   one code reference in `AbstractProtectionHookSpecification`, purely to see
   what failed next. Every remaining hook compiled, and the build reached
   `spigot-core`, which then failed on `inventorygui` and `rcplatform-api`
   together. That experiment was reverted and is not committed.

---

## 5. Test coverage, stated precisely

- `animatedarchitecture-core`: **402 tests, all passing.**
- `animatedarchitecture-testing`, `animatedarchitecture-integration-test`, and
  everything in the Spigot layer: **never executed**, because they sit
  downstream of modules that cannot resolve their dependencies.

Do not describe the suite as green. Core is green. The Spigot layer is unverified.

---

## 6. Settled, no longer open

`maven.compiler.release=25` is correct for this deployment. The production host
runs JDK 25. The earlier handoff flagged this as an open `UnsupportedClassVersionError`
risk. It is closed, and it should not be raised again.
