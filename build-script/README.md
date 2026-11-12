# BuildScript

Edit `build.gradle.kts` files from code

```kotlin
val script = BuildScript.open(Path.of("app/build.gradle.kts"))
script.pin("com.squareup.okhttp3:okhttp:5.5.1")
script.save()
```

```diff
 dependencies {
-    implementation("com.squareup.okhttp3:okhttp:5.5.0") // http client
+    implementation("com.squareup.okhttp3:okhttp:5.5.1") // http client
 }
```

Comments, spacing, and everything else stay exactly as written

Works with dependencies and plugins in Kotlin DSL build scripts

Every example here is covered by [BuildScriptSpec](src/test/kotlin/cash/buildscript/BuildScriptSpec.kt). Nothing is published yet: build it from this repository.

## Dependencies

List them

```kotlin
script.dependencies
// [
//   implementation("com.squareup.okhttp3:okhttp:5.5.0"),
//   implementation(libs.picocli),
//   implementation(project(":core")),
//   testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
// ]
```

Add one

```kotlin
script.add("com.squareup.okio:okio:3.18.2")
```

Or name the version

```kotlin
script.add("com.squareup.okio:okio", version = "3.18.2")
```

To another configuration

```kotlin
script.add("org.assertj:assertj-core:3.27.7", configuration = "testImplementation")
```

As a platform

```kotlin
script.add("tools.jackson:jackson-bom:3.3.0", configuration = "api", modifier = Platform)
```

From the version catalog

```kotlin
script.add("libs.okio")
```

Or another project

```kotlin
script.add(":core")
```

Pin a version

```kotlin
script.pin("com.squareup.okio:okio", version = "3.19.0")
```

Remove one

```kotlin
script.remove("com.squareup.moshi:moshi")
```

From one configuration only

```kotlin
script.remove("com.squareup.okio:okio", configuration = "testImplementation")
```

## Plugins

List them

```kotlin
script.pluginDependencies
// [
//   application,
//   kotlin("jvm") version "2.2.0",
//   id("org.graalvm.buildtools.native") version "1.1.17"
// ]
```

Add one

```kotlin
script.add(PluginId("org.jetbrains.kotlin.jvm"), version = "2.2.21")
```

Pin a version

```kotlin
script.pin(PluginId("org.jetbrains.kotlin.jvm"), version = "2.3.0")
```

Remove one

```kotlin
script.remove(PluginId("org.jetbrains.kotlin.jvm"))
```

## Saving

See the result

```kotlin
script.source
```

Write it back

```kotlin
script.save()
```

## Strings

A string is read by its shape

```kotlin
script.add("com.squareup.okio:okio:3.18.2")   // Group, Name and Version
script.add("com.squareup.okio:okio")          // Group and Name, no Version
script.add("com.squareup.okio:okio", version = "3.18.2")
script.add("libs.okio")                       // version catalog
script.add(":core")                           // another project
```

The configuration is `implementation` unless you name one. Give the version in the string or by name, not both. Strings are written as given: a typo is reported by Gradle the next time the build runs.

## Being Explicit

Every short form has a full form

```kotlin
val okio = Module("com.squareup.okio", "okio")
script.add(ExternalModuleDependency(Configuration("implementation"), ModuleVersion(okio, RequiredVersion("3.18.2")), Unmodified))

val kotlinJvm = PluginId("org.jetbrains.kotlin.jvm")
script.add(Plugin(kotlinJvm, RequiredVersion("2.2.21")))
```

## When It Can't

Adding what is already declared changes nothing

```kotlin
script.add("com.squareup.okio:okio:3.18.2")
script.add("com.squareup.okio:okio:3.18.2")
```

Adding it at a different version fails. Use `pin`

```kotlin
script.pin("com.squareup.okio:okio", version = "3.19.0")
```

Pinning or removing what is not declared fails

```kotlin
script.remove("com.example:not-there")
```

Each failure is a `BuildScriptFailure` and leaves the script as it was

| Failure | When |
| --- | --- |
| `ConflictingVersion` | `add` names something declared at a different version |
| `UndeclaredDependency`, `UndeclaredPlugin` | `pin` or `remove` names something the script does not declare |
| `MissingVersion` | `pin` is given no version, or the plugin is declared without one |
| `VersionInCatalog` | `pin` is given a version catalog accessor |
| `UnreadableDependency` | a string has none of the shapes, or its version is given twice |

## What Is Listed

Only what the library models. These stay in the script as written and are left out of the lists

```kotlin
implementation(files("libs/local.jar"))
implementation(projects.core)
implementation("com.example:tool:1.0:all@zip")
kotlin("jvm") version kotlinVersion
```

Pinning a module also changes the version of its declarations with a classifier or an extension

## Maven Central

List the versions a module has published

```kotlin
val okio = Module("com.squareup.okio", "okio")
val published = MavenCentral().listVersions(okio)
// [
//   com.squareup.okio:okio:0.5.0,
//   ...
//   com.squareup.okio:okio:3.18.2
// ]
```

Pin to one of them

```kotlin
script.pin(published.last())
```

The list is in the repository's own order, which is not always oldest to newest, and includes pre-releases. Which version to use is your choice

Searching by keyword is specified and not built yet

## Version Catalogs

`libs.versions.toml` is not read or changed, so the library does not know which module `libs.okio` is

```kotlin
script.pin("libs.okio", version = "3.19.0")              // fails: VersionInCatalog
script.pin("com.squareup.okio:okio", version = "3.19.0") // fails: UndeclaredDependency, when only libs.okio is declared
```

Change the version in the catalog
