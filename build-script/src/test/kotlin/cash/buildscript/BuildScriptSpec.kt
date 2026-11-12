package cash.buildscript

import cash.buildscript.DependencyModifier.EnforcedPlatform
import cash.buildscript.DependencyModifier.Platform
import cash.buildscript.DependencyModifier.TestFixtures
import cash.buildscript.DependencyModifier.Unmodified
import cash.grammar.kotlindsl.parse.KotlinParseException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

// Specs for the object layer. A spec stays @Disabled("pending") until it is written.
//
// Wording: a name in CamelCase (ExternalModuleDependency, ModuleVersion) is a type in the layer. A capitalized
// word that is not a type (Group, Name, Version Catalog) is a domain term. Kotlin does not allow a period or a
// colon in a test name, so strings are described, not quoted.
//
// A plugin has no plain string form: Kotlin would resolve a bare string to the wrong overload, so the short form for
// plugins takes a PluginId.
internal class BuildScriptSpec {

  @TempDir lateinit var directory: Path

  private val implementation = Configuration("implementation")
  private val okio = Module("com.squareup.okio", "okio")

  private fun open(source: String): BuildScript {
    val file = directory.resolve("build.gradle.kts")
    Files.writeString(file, source)
    return BuildScript.open(file)
  }

  @Nested inner class `opening a build script` {

    @Test fun `lists each declaration in the dependencies block as a Dependency`() {
      val script = open(
        """
          dependencies {
            implementation("com.squareup.okio:okio:3.18.2")
            implementation(libs.picocli)
            implementation(project(":core"))
            testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
          }
        """.trimIndent()
      )

      assertThat(script.dependencies).containsExactly(
        ExternalModuleDependency(implementation, ModuleVersion(okio, RequiredVersion("3.18.2"))),
        CatalogDependency(implementation, TypeSafeAccessor("libs.picocli")),
        ProjectDependency(implementation, ProjectPath(":core")),
        ExternalModuleDependency(
          Configuration("testImplementation"),
          ModuleVersion(Module("org.junit.jupiter", "junit-jupiter"), RequiredVersion("6.1.3")),
        ),
      )
    }

    @Test fun `reads a declaration with a Group, a Name and a version as an ExternalModuleDependency on a ModuleVersion with a RequiredVersion`() {
      val script = open("""dependencies { implementation("com.squareup.okio:okio:3.18.2") }""")

      val dependency = script.dependencies.single() as ExternalModuleDependency
      assertThat(dependency.moduleVersion.module.group).isEqualTo("com.squareup.okio")
      assertThat(dependency.moduleVersion.module.name).isEqualTo("okio")
      assertThat(dependency.moduleVersion.version).isEqualTo(RequiredVersion("3.18.2"))
    }

    @Test fun `reads a declaration with a Group and a Name only as a ModuleVersion with UnspecifiedVersion`() {
      val script = open("""dependencies { testRuntimeOnly("org.junit.platform:junit-platform-launcher") }""")

      val dependency = script.dependencies.single() as ExternalModuleDependency
      assertThat(dependency.moduleVersion)
        .isEqualTo(ModuleVersion(Module("org.junit.platform", "junit-platform-launcher"), UnspecifiedVersion))
    }

    @Test fun `reads a declaration written through the Version Catalog as a CatalogDependency with its TypeSafeAccessor`() {
      val script = open("""dependencies { implementation(libs.okhttp) }""")

      assertThat(script.dependencies)
        .containsExactly(CatalogDependency(implementation, TypeSafeAccessor("libs.okhttp")))
    }

    @Test fun `reads project() as a ProjectDependency with its ProjectPath`() {
      val script = open("""dependencies { implementation(project(":core")) }""")

      assertThat(script.dependencies).containsExactly(ProjectDependency(implementation, ProjectPath(":core")))
    }

    @Test fun `reads platform(), enforcedPlatform() and testFixtures() as the DependencyModifier`() {
      val script = open(
        """
          dependencies {
            api(platform("tools.jackson:jackson-bom:3.3.0"))
            api(enforcedPlatform(libs.bom))
            testImplementation(testFixtures(project(":core")))
          }
        """.trimIndent()
      )

      assertThat(script.dependencies.map { it.modifier }).containsExactly(Platform, EnforcedPlatform, TestFixtures)
      assertThat(script.dependencies.map { it::class })
        .containsExactly(ExternalModuleDependency::class, CatalogDependency::class, ProjectDependency::class)
    }

    @Test fun `reads a declaration with no modifier as Unmodified`() {
      val script = open("""dependencies { implementation("com.squareup.okio:okio:3.18.2") }""")

      assertThat(script.dependencies.single().modifier).isEqualTo(Unmodified)
    }

    @Test fun `reads the Configuration of each Dependency`() {
      val script = open(
        """
          dependencies {
            api("com.squareup.okio:okio:3.18.2")
            testImplementation(libs.junit)
            "integrationTestImplementation"(project(":core"))
          }
        """.trimIndent()
      )

      assertThat(script.dependencies.map { it.configuration.name })
        .containsExactly("api", "testImplementation", "integrationTestImplementation")
    }

    @Test fun `does not list a declaration the layer does not model`() {
      val script = open(
        """
          val okioVersion = "3.18.2"

          dependencies {
            implementation(files("libs/local.jar"))
            implementation(projects.core)
            implementation("com.example:tool:1.0:all@zip")
            implementation("com.example:archive:1.0@zip")
            implementation("com.squareup.okio:okio:${'$'}okioVersion")
            implementation("com.squareup.okhttp3:okhttp:5.5.1")
          }
        """.trimIndent()
      )

      assertThat(script.dependencies).containsExactly(
        ExternalModuleDependency(
          implementation,
          ModuleVersion(Module("com.squareup.okhttp3", "okhttp"), RequiredVersion("5.5.1")),
        ),
      )
    }

    @Test fun `lists each entry in the plugins block as a PluginDependency`() {
      val script = open(
        """
          plugins {
            application
            `java-library`
            kotlin("jvm") version "2.2.0"
            id("org.graalvm.buildtools.native") version "1.1.17"
            alias(libs.plugins.shadow)
          }
        """.trimIndent()
      )

      assertThat(script.pluginDependencies).containsExactly(
        Plugin(PluginId("application")),
        Plugin(PluginId("java-library")),
        Plugin(PluginId("org.jetbrains.kotlin.jvm"), RequiredVersion("2.2.0")),
        Plugin(PluginId("org.graalvm.buildtools.native"), RequiredVersion("1.1.17")),
        CatalogPlugin(TypeSafeAccessor("libs.plugins.shadow")),
      )
    }

    @Test fun `reads id() with a version as a Plugin with its PluginId and a RequiredVersion`() {
      val script = open("""plugins { id("com.example.plugin") version "1.0" apply false }""")

      assertThat(script.pluginDependencies)
        .containsExactly(Plugin(PluginId("com.example.plugin"), RequiredVersion("1.0")))
    }

    @Test fun `reads a plugin declared without a version as UnspecifiedVersion`() {
      val script = open("""plugins { id("com.example.convention") }""")

      assertThat((script.pluginDependencies.single() as Plugin).version).isEqualTo(UnspecifiedVersion)
    }

    @Test fun `reads alias() as a CatalogPlugin with its TypeSafeAccessor`() {
      val script = open("""plugins { alias(libs.plugins.kotlin.jvm) }""")

      assertThat(script.pluginDependencies)
        .containsExactly(CatalogPlugin(TypeSafeAccessor("libs.plugins.kotlin.jvm")))
    }

    @Test fun `does not list a plugin whose version is not a plain string`() {
      val script = open(
        """
          val kotlinVersion = "2.2.0"

          plugins {
            kotlin("jvm") version kotlinVersion
            application
          }
        """.trimIndent()
      )

      assertThat(script.pluginDependencies).containsExactly(Plugin(PluginId("application")))
    }

    @Test fun `ignores dependencies blocks and plugins blocks that are not top-level`() {
      val script = open(
        """
          pluginManagement {
            plugins {
              id("com.example.managed") version "1.0"
            }
          }

          buildscript {
            dependencies {
              classpath("com.example:gradle-plugin:1.0")
            }
          }

          plugins {
            application
          }

          dependencies {
            implementation("com.squareup.okio:okio:3.18.2")
          }
        """.trimIndent()
      )

      assertThat(script.pluginDependencies).containsExactly(Plugin(PluginId("application")))
      assertThat(script.dependencies)
        .containsExactly(ExternalModuleDependency(implementation, ModuleVersion(okio, RequiredVersion("3.18.2"))))
    }

    @Test fun `returns the source byte-for-byte when nothing has changed`() {
      val source =
        """
          // the app
          plugins {
              application   // runs it
          }

          dependencies {
            implementation(  "com.squareup.okio:okio:3.18.2"  )


            runtimeOnly("org.xerial:sqlite-jdbc:3.54.1.0") {
              isTransitive = false
            }
          }

        """.trimIndent()

      assertThat(open(source).source).isEqualTo(source)
    }

    @Test fun `fails when the script does not parse`() {
      assertThatThrownBy { open("dependencies {\n  implementation(\"com.squareup.okio:okio:3.18.2\"\n}") }
        .isInstanceOf(KotlinParseException::class.java)
    }
  }

  @Nested inner class `adding a Dependency` {

    @Disabled("pending") @Test fun `adds an ExternalModuleDependency after the last declaration on the same Configuration`() {}

    @Disabled("pending") @Test fun `adds a CatalogDependency`() {}

    @Disabled("pending") @Test fun `adds a ProjectDependency`() {}

    @Disabled("pending") @Test fun `writes the DependencyModifier around the dependency`() {}

    @Disabled("pending") @Test fun `creates the dependencies block when the script has none`() {}

    @Disabled("pending") @Test fun `adds the same Module again on a different Configuration`() {}

    @Disabled("pending") @Test fun `changes nothing when the Module is on that Configuration at the same Version`() {}

    @Disabled("pending") @Test fun `fails with ConflictingVersion, pointing to pin, when the Module is on that Configuration at a different Version`() {}
  }

  @Nested inner class `adding a Dependency from a string` {

    @Disabled("pending") @Test fun `reads three segments as a Group, a Name and a RequiredVersion`() {}

    @Disabled("pending") @Test fun `reads two segments as a Group and a Name with UnspecifiedVersion`() {}

    @Disabled("pending") @Test fun `reads a dotted string with no colon as a TypeSafeAccessor`() {}

    @Disabled("pending") @Test fun `reads a string that starts with a colon as a ProjectPath`() {}

    @Disabled("pending") @Test fun `takes the Version as a named argument instead of a third segment`() {}

    @Disabled("pending") @Test fun `uses the implementation Configuration when none is named`() {}

    @Disabled("pending") @Test fun `uses the Configuration that is named`() {}

    @Disabled("pending") @Test fun `uses Unmodified when no DependencyModifier is named`() {}

    @Disabled("pending") @Test fun `uses the DependencyModifier that is named`() {}

    @Disabled("pending") @Test fun `writes the string as given without checking that it resolves`() {}

    @Disabled("pending") @Test fun `fails with UnreadableDependency when the string has none of the four shapes`() {}

    @Disabled("pending") @Test fun `fails with UnreadableDependency when the Version is given both in the string and as a named argument`() {}

    @Disabled("pending") @Test fun `fails with UnreadableDependency when a Version is named for a TypeSafeAccessor or a ProjectPath`() {}
  }

  @Nested inner class `pinning a ModuleVersion` {

    @Disabled("pending") @Test fun `sets the Version of that Module on every Configuration it is declared on`() {}

    @Disabled("pending") @Test fun `accepts a ModuleVersion or its string form`() {}

    @Disabled("pending") @Test fun `takes the Version as a named argument instead of a third segment`() {}

    @Disabled("pending") @Test fun `keeps the DependencyModifier, a trailing block and comments`() {}

    @Disabled("pending") @Test fun `changes nothing when the Module is already at that Version`() {}

    @Disabled("pending") @Test fun `fails with UndeclaredDependency when the Module is not declared`() {}

    @Disabled("pending") @Test fun `fails with VersionInCatalog when the Module is declared only as a CatalogDependency`() {}

    @Disabled("pending") @Test fun `fails with MissingVersion when no Version is given, in the string or by name`() {}

    @Disabled("pending") @Test fun `fails with UnreadableDependency when the Version is given both in the string and as a named argument`() {}
  }

  @Nested inner class `removing a Dependency` {

    @Disabled("pending") @Test fun `removes the declaration and its line`() {}

    @Disabled("pending") @Test fun `matches on the Group and the Name, whatever the Version`() {}

    @Disabled("pending") @Test fun `removes from the named Configuration only`() {}

    @Disabled("pending") @Test fun `removes from every Configuration when none is named`() {}

    @Disabled("pending") @Test fun `removes a CatalogDependency`() {}

    @Disabled("pending") @Test fun `removes a ProjectDependency`() {}

    @Disabled("pending") @Test fun `fails with UndeclaredDependency when it is not declared`() {}
  }

  @Nested inner class `adding a PluginDependency` {

    @Disabled("pending") @Test fun `adds a Plugin to the plugins block`() {}

    @Disabled("pending") @Test fun `adds a CatalogPlugin`() {}

    @Disabled("pending") @Test fun `creates the plugins block when the script has none`() {}

    @Disabled("pending") @Test fun `changes nothing when that PluginId is there at the same Version`() {}

    @Disabled("pending") @Test fun `fails with ConflictingVersion, pointing to pin, when that PluginId is there at a different Version`() {}
  }

  @Nested inner class `pinning a Plugin` {

    @Disabled("pending") @Test fun `sets the Version of that PluginId`() {}

    @Disabled("pending") @Test fun `keeps apply false and everything else on the line`() {}

    @Disabled("pending") @Test fun `changes nothing when the Plugin is already at that Version`() {}

    @Disabled("pending") @Test fun `fails with UndeclaredPlugin when that PluginId is not declared`() {}

    @Disabled("pending") @Test fun `fails with MissingVersion when the Plugin is declared without a Version`() {}

    @Disabled("pending") @Test fun `fails with VersionInCatalog when the plugin is declared as a CatalogPlugin`() {}
  }

  @Nested inner class `removing a PluginDependency` {

    @Disabled("pending") @Test fun `removes the entry and its line`() {}

    @Disabled("pending") @Test fun `fails with UndeclaredPlugin when it is not declared`() {}
  }

  @Nested inner class `naming a plugin by its PluginId` {

    @Disabled("pending") @Test fun `adds a Plugin from a PluginId and a named Version`() {}

    @Disabled("pending") @Test fun `adds a Plugin from a PluginId alone, with UnspecifiedVersion`() {}

    @Disabled("pending") @Test fun `pins a Plugin from a PluginId and a named Version`() {}

    @Disabled("pending") @Test fun `fails with MissingVersion to pin a Plugin when no Version is named`() {}

    @Disabled("pending") @Test fun `removes a Plugin from a PluginId alone, whatever its Version`() {}
  }

  @Nested inner class `keeping the rest of the script as written` {

    @Disabled("pending") @Test fun `leaves every line an operation did not change byte-for-byte`() {}

    @Disabled("pending") @Test fun `leaves a declaration the layer does not model as written`() {}

    @Disabled("pending") @Test fun `leaves the script unchanged when an operation fails`() {}
  }

  @Nested inner class `saving` {

    @Disabled("pending") @Test fun `writes the source back to the file it was opened from`() {}

    @Disabled("pending") @Test fun `leaves the file untouched when nothing has changed`() {}

    @Disabled("pending") @Test fun `applies several operations in the order they were called`() {}

    @Disabled("pending") @Test fun `reads the same parts after saving and opening again`() {}
  }
}
