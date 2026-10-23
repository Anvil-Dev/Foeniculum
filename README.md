<div align="center">

# Foeniculum

**A ModDevGradle companion plugin that slims and flattens nested jar-in-jar dependencies into a single layer.**

English | [简体中文](README.zh_CN.md)

</div>

Foeniculum is a Gradle plugin for ModDevGradle (MDG). Its plugin id is `dev.anvilcraft.resource.foeniculum` and its Maven coordinate is `dev.anvilcraft.resource:foeniculum-gradle-plugin`. It is released under GNU LGPL 3.0.

It provides a mechanism called `slimJarJar`: libraries publish a slim artifact plus a standalone descriptor file, and the top-level project reads those descriptors recursively, flattens the whole dependency tree into one layer, and writes the result into the main jar's standard FML metadata alongside the native `jarJar` entries. None of this changes or interferes with how MDG's native `jarJar` behaves.

## Why the name

The above-ground part of fennel has one thick main stalk, from which a great many fine, feathery branches grow.

This plugin compares its slim artifacts — `-slim.jar` and `-slim-metadata.json` — to those branches: they travel from library to library through Maven, one layer at a time, and each layer carries only its own segment of the dependency description. Only the top-level product, the main stalk, aggregates all those branches and flattens them into the single piece of metadata inside the main jar.

## The problem

The usual way to distribute a library in the NeoForge ecosystem is Jar-in-Jar (JiJ): embed the dependency's jar inside your own jar, and let FML unpack and load it at runtime. MDG ships a `jarJar` configuration and a task of the same name for this; a plugin author only has to declare a dependency in `jarJar`, and the artifact will contain the embedded jar and the FML metadata.

The problem appears when several libraries depend on each other and each of them also `jarJar`s its own downstream libraries. Suppose A depends on B and C, and B itself embeds C. With native `jarJar`, A embeds both B and C, while B carries another copy of C inside it — so C ends up in A's artifact twice. Jar size grows, and at runtime the same module may even be loaded in multiple versions and conflict.

MDG's native `jarJar` addresses the level of "one project embedding a few direct dependencies"; it has no knowledge of what downstream libraries embed internally. The duplication therefore has to be solved in how libraries are published.

## The approach

The plugin splits the responsibility in two: intermediate libraries stay *thin*, the top-level project makes things *flat*.

With this plugin applied, an intermediate library can produce two extra artifacts: a slim jar `-slim.jar` and a standalone descriptor `-slim-metadata.json`. The slim jar still keeps the project's own native `jarJar` embedded content — unchanged from what it would be without the plugin — but it no longer packs the actual `slimJarJar` dependencies; those are recorded in the descriptor as coordinates and version constraints.

Once the top-level project declares dependencies with `slimJarJar`, the build walks down through the descriptors, collects the whole tree, intersects all version constraints for the same module and decides on a single version, drops modules that duplicate the native `jarJar` set, and finally puts both the flattened embedded jars and the native ones into the main jar with one merged `metadata.json`.

However deep the tree is nested, every module appears exactly once under the main jar's `META-INF/jarjar/`.

## Usage

```groovy
plugins {
    id 'java-library'
    id 'maven-publish'
    id 'net.neoforged.moddev' version '<MDG version>'
    id 'dev.anvilcraft.resource.foeniculum' version '<Foeniculum version>'
}

foeniculum {
    // Whether to attach the slim jar and descriptor to the maven-publish publication. Defaults to true.
    autoAttachPublishing = true
}

dependencies {
    jarJar 'com.example:legacy-lib:1.0.0'                          // Native MDG; Foeniculum does not touch it
    slimJarJar 'com.example.lib:core-lib:[2.0.0,3.0.0)'             // Version ranges are supported
    slimJarJar 'com.example.lib:util-lib:1.4.2'                    // Fixed versions work too
}
```

`autoAttachPublishing` only has an effect when the project applies `maven-publish`. When enabled, the slim jar is attached with classifier `slim` and the descriptor with classifier `slim-metadata`, so there is no need to declare the `artifact` entries by hand. When disabled, the plugin leaves the publishing configuration untouched and the project decides how to publish the two files.

## The three artifacts

| Artifact | Path | classifier / extension | Contents |
| --- | --- | --- | --- |
| Main jar | `build/libs/<name>-<version>.jar` | none / `jar` | The project's own code; under `META-INF/jarjar/` live the native `jarJar` embedded jars, the flattened slim embedded jars, and one merged `metadata.json` |
| Slim jar | `build/libs/<name>-<version>-slim.jar` | `slim` / `jar` | The project's own code; under `META-INF/jarjar/` only the native `jarJar` embedded jars and the native `metadata.json`, identical to the artifact without the plugin; if the project has no native `jarJar` dependencies, the `META-INF/jarjar/` directory does not exist at all |
| Descriptor | `build/foeniculum/publish/<name>-<version>-slim-metadata.json` | `slim-metadata` / `json` | Only the dependencies declared directly through `slimJarJar`, plus the repository list the project declares (for reference only) |

The main jar is what ships to end users and carries the entire aggregation duty. The slim jar is what an intermediate library publishes to a repository; it stays as it is, so no amount of nesting downstream accumulates duplicates. The descriptor is not packaged — it is published to the Maven repository next to the slim jar and read by downstream builds.

All three are produced in a single build; their file names follow the project's `archivesName` and `version`, matching the main jar.

## Descriptor format

`-slim-metadata.json` looks like this:

```json
{
  "schemaVersion": 1,
  "repositories": [
    "https://repo1.maven.org/maven2",
    "https://maven.example.com/releases"
  ],
  "dependencies": [
    {
      "group": "com.example.lib",
      "artifact": "core-lib",
      "versionRange": "[2.0.0,3.0.0)",
      "preferredVersion": "2.4.1",
      "isSlim": true
    }
  ]
}
```

`schemaVersion` is fixed at `1`. A higher version is rejected with an error asking to upgrade the plugin, so an old plugin cannot silently misread a newer format.

`repositories` is purely informational: downstream resolves against it only by comparing it with its own repositories. **Repositories are never injected automatically.**

`dependencies` records direct declarations only and does not expand transitive ones: the shape of the tree is assembled by each layer's descriptor, and every layer is responsible only for what it declares. `isSlim` indicates whether the dependency is embedded as a slim jar (`-slim.jar`) — `true` in the vast majority of cases, and `false` only when the downstream library provides no slim jar and the standard jar is used instead.

## FML metadata in the main jar

`META-INF/jarjar/metadata.json` in the main jar is the standard JiJ metadata defined by NeoForge FML, and the entries Foeniculum generates match MDG's native format exactly:

```json
{
  "jars": [
    {
      "identifier": { "group": "com.example", "artifact": "legacy-lib" },
      "version": { "range": "[1.0.0,)", "artifactVersion": "1.0.0" },
      "path": "META-INF/jarjar/legacy-lib-1.0.0.jar",
      "isObfuscated": false
    },
    {
      "identifier": { "group": "com.example.lib", "artifact": "core-lib" },
      "version": { "range": "[2.0.0,3.0.0)", "artifactVersion": "2.0.1" },
      "path": "META-INF/jarjar/core-lib-2.0.1-slim.jar",
      "isObfuscated": false
    }
  ]
}
```

Native entries keep MDG's file names; jars embedded by Foeniculum carry a `-slim` suffix (omitted when falling back to a standard jar), so it is immediately obvious whether an embedded jar is a branch or a plain jar. If two modules with the same artifact, version, and conflicting groups collide, the group prefix is prepended during resolution to tell them apart.

The merge rules are deterministic: native `jarJar` entries are preserved verbatim and in order, slim entries are appended after them sorted by `group:artifact`, and when the same `group:artifact` appears on both sides the native side is kept with a Gradle warning. The warning is a notice rather than an error because a native declaration is something the project wrote explicitly and should take precedence.

"Keep the native side" has one precondition: the natively embedded version must fall within the range the slim side declares for that module. If the native side embeds `1.0.0` while a downstream descriptor requires `[2.0,3.0)`, FML at runtime compares the range against the actually embedded version and reports a Jar-in-Jar conflict. Such cases fail the build with both values named, rather than producing an artifact that builds fine and crashes on startup. The fix is to move the `jarJar` version into that range, or to drop one of the two declarations.

## Recursive resolution and flattening

The build starts from the direct `slimJarJar` declarations and fetches `group/artifact/version/artifact-version-slim-metadata.json` from the Maven repositories the project declares, then recurses along its `dependencies` until a layer has no descriptor. A module without a descriptor is a leaf of the tree and is embedded directly.

Artifacts are taken from `artifact-version-slim.jar` first; when it is absent the build falls back to `artifact-version.jar`, sets `isSlim` to `false`, and emits a warning. The fallback matters: not every downstream library will switch to this plugin immediately, and embedding a plain jar as a leaf is more reasonable than failing the whole build.

The tree is then flattened into one layer. All `versionRange` values seen for a module are intersected; the highest version among the candidates (the observed `preferredVersion` values and so on) that satisfies the intersection is chosen; if none does, the build falls back to the highest version in the repository's `maven-metadata.xml` that satisfies it. An empty intersection, or no satisfying version at all, raises a build exception listing each constraint's origin so it is clear which layer narrowed the range beyond satisfiability.

Conflicts are not judged from intermediate states of the iteration. Once a module is raised to a different version, the dependencies its old version declared no longer hold — but before that happens, the old version's constraints may already have collapsed a downstream module's range to an empty set. Such an empty set is only temporary, and failing on it immediately would break a dependency graph that does have a solution. The iteration therefore keeps the previous choice when it hits an empty intersection and lets other modules keep evolving; a conflict is reported only when a full round of decisions is stable and an empty intersection still remains.

Merged version ranges are normalized: a fixed version is rewritten as a range with only a lower bound, so `1.2.3` becomes `[1.2.3,)`. Descriptors thus only ever use one form, and downstream comparison logic does not need to distinguish fixed versions from ranges.

Deduplication happens between the native `jarJar` set and the slim set: when the same `group:artifact` exists on both sides, the slim entry is dropped and the native one is kept verbatim. This also covers the case where a project already embeds a library through native `jarJar` while a downstream descriptor brings it in again, since that shows up as a name collision during merging.

If a downstream descriptor mentions a repository the current project has not declared, the plugin only emits a clear Gradle warning; it never modifies `project.repositories`. The set of repositories at build time belongs to the project, and the plugin does not make network access policy decisions on its behalf.

## Explicitly unsupported

Only the `main` source set is handled. Artifacts from other source sets are outside this plugin's scope.

`slimJarJar` does not accept project dependencies. Declaring one raises an exception explaining why: the mechanism relies on artifacts flowing through Maven repositories, and a project dependency has no publishable coordinate and no descriptor for downstream to read. Forcing support would only produce an unreproducible resolution path.

Dynamic versions (such as `1.+` or `latest.release`) are rejected. Use a fixed version or a version range instead; the resolved result must be uniquely determined by the descriptor itself.

## Build and test

The plugin is an ordinary Gradle project built with JDK 21:

```shell
./gradlew build
```

`build` compiles, runs the unit tests, and executes `functionalTest`, which drives the plugin inside temporary projects through the Gradle TestKit to verify resolution, flattening, and packaging. Among them is a case that coexists with a real ModDevGradle setup. That case deliberately does not configure the `neoForge` block, so as long as no Minecraft-related task is touched, no Minecraft or server-side resources need to be downloaded.

## Continuous integration and publishing

The GitHub Actions workflows are layered, with `build_and_test.yml` providing the reusable build steps:

| Workflow | Trigger | Behavior |
| --- | --- | --- |
| `pull_request.yml` | Pull request targeting `main` | Runs `./gradlew build` only; publishes nothing |
| `ci.yml` | Push to `main` or manual dispatch | Runs the build, then publishes the artifacts to the private Maven |
| `release.yml` | GitHub Release published or manual dispatch | Runs the build, publishes to the private Maven, then submits the staging content to Maven Central |

All workflows run on `ubuntu-latest` with Zulu JDK 21 and only hold `contents: read` permission. The build first validates the Gradle Wrapper with `gradle/actions/wrapper-validation` so a tampered wrapper cannot enter the build.

Publishing to Maven Central goes through Sonatype's Portal API: `maven-publish` first writes the artifacts into the local `build/staging-deploy`, then JReleaser signs them, validates the rules, and uploads the submission. This way a failed publication leaves no half-published state, and the contents of the staging directory can be inspected before submission.

The secrets referenced by the workflows match those of AnvilLib:

| Secret | Purpose |
| --- | --- |
| `MAVEN_URL` / `MAVEN_USER` / `MAVEN_PASS` | Private Maven repository URL and credentials |
| `MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_PASSWORD` | Portal token for Maven Central |
| `SIGNING_KEY` / `SIGNING_PUBLIC_KEY` | Armored text of the GPG key pair |
| `SIGNING_PASSWORD` | Passphrase of the GPG private key |

When private Maven credentials are missing (for example on a pull request from a fork) that publishing step is skipped automatically while the build and tests still run. Maven Central credentials are passed only in `release.yml`, so a push to `main` never triggers a Central publication.

## License

This project is released under the GNU Lesser General Public License 3.0. Use, modification, and redistribution are subject to its terms, especially the requirement that modified works remain open source under the same license.
