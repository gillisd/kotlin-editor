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

> **Status: design.** This README describes the planned API. It is specified by the pending specs in
> [BuildScriptSpec](src/test/kotlin/cash/buildscript/BuildScriptSpec.kt) and is not implemented or published yet.
> The name is provisional.

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
script.pin("com.squareup.okio:okio:3.18.2")
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
val kotlinJvm = PluginId("org.jetbrains.kotlin.jvm")
script.add(PluginDependency(kotlinJvm, RequiredVersion("2.2.21")))
```

Pin a version

```kotlin
script.pin(PluginDependency(kotlinJvm, RequiredVersion("2.2.21")))
```

Remove one

```kotlin
script.remove(PluginDependency(kotlinJvm, RequiredVersion("2.2.21")))
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
script.add("libs.okio")                       // version catalog
script.add(":core")                           // another project
```

The configuration is `implementation` unless you name one. Strings are written as given: a typo is reported by Gradle the next time the build runs.

## Being Explicit

Every string has a full form

```kotlin
val okio = Module("com.squareup.okio", "okio")
script.add(ExternalModuleDependency(Configuration("implementation"), ModuleVersion(okio, RequiredVersion("3.18.2")), Unmodified))
```

## When It Can't

Adding what is already declared changes nothing

```kotlin
script.add("com.squareup.okio:okio:3.18.2")
script.add("com.squareup.okio:okio:3.18.2")
```

Adding it at a different version fails. Use `pin`

```kotlin
script.pin("com.squareup.okio:okio:3.19.0")
```

Pinning or removing what is not declared fails

```kotlin
script.remove("com.example:not-there")
```

## Version Catalogs

Versions in `libs.versions.toml` are not changed. Pinning a dependency declared as `libs.okio` fails and says so.
