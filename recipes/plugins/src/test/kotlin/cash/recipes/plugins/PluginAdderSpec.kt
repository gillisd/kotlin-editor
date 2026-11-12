package cash.recipes.plugins

import cash.grammar.kotlindsl.parse.KotlinParseException
import cash.recipes.plugins.PluginAdder.Addition.ByAlias
import cash.recipes.plugins.PluginAdder.Addition.ById
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

internal class PluginAdderSpec {

  @Nested inner class `adding to an existing plugins block` {

    @Test fun `adds a plugin with a version after the last entry`() {
      // Given
      val buildScript =
        """
          plugins {
            application
            kotlin("jvm") version "2.2.0" // the language
          }

          dependencies {
            implementation("com.squareup.okio:okio:3.9.0")
          }
        """.trimIndent()

      // When
      val adder = PluginAdder.of(buildScript, listOf(ById("org.graalvm.buildtools.native", "1.1.18")))

      // Then
      assertThat(adder.rewritten()).isEqualTo(
        """
          plugins {
            application
            kotlin("jvm") version "2.2.0" // the language
            id("org.graalvm.buildtools.native") version "1.1.18"
          }

          dependencies {
            implementation("com.squareup.okio:okio:3.9.0")
          }
        """.trimIndent()
      )
    }

    @Test fun `adds a plugin without a version, and a version catalog alias, in the order given`() {
      // Given
      val buildScript =
        """
          plugins {
            application
          }
        """.trimIndent()

      // When
      val adder = PluginAdder.of(
        buildScript,
        listOf(ById("com.example.convention"), ByAlias("libs.plugins.shadow"), ById("com.example.plugin", "2.0")),
      )

      // Then
      assertThat(adder.rewritten()).isEqualTo(
        """
          plugins {
            application
            id("com.example.convention")
            alias(libs.plugins.shadow)
            id("com.example.plugin") version "2.0"
          }
        """.trimIndent()
      )
    }

    @Test fun `skips a plugin that is already in the block, in any form`() {
      // Given every way of writing a plugin
      val buildScript =
        """
          plugins {
            application
            `java-library`
            kotlin("jvm") version "2.2.0"
            id("com.example.plugin") version "1.0" apply false
            alias(libs.plugins.shadow)
          }
        """.trimIndent()
      val additions = listOf(
        ById("application"),
        ById("java-library"),
        ById("org.jetbrains.kotlin.jvm", "2.2.21"),
        ById("com.example.plugin", "2.0"),
        ByAlias("libs.plugins.shadow"),
      )

      // When
      val adder = PluginAdder.of(buildScript, additions)

      // Then nothing changes, whatever version was asked for
      assertThat(adder.isChanged()).isFalse()
      assertThat(adder.rewritten()).isEqualTo(buildScript)
      assertThat(adder.skipped()).containsExactlyElementsOf(additions)
    }

    @Test fun `adds to an empty multi-line block and to an empty one-line block`() {
      assertThat(PluginAdder.of("plugins {\n}\n", listOf(ById("application"))).rewritten())
        .isEqualTo("plugins {\n  id(\"application\")\n}\n")
      assertThat(PluginAdder.of("plugins {}\n", listOf(ById("application"))).rewritten())
        .isEqualTo("plugins {\n  id(\"application\")\n}\n")
    }

    @Test fun `stays on the line when the closing brace shares it with the last entry`() {
      assertThat(PluginAdder.of("plugins { application }\n", listOf(ById("com.example.plugin", "1.0"))).rewritten())
        .isEqualTo("plugins { application; id(\"com.example.plugin\") version \"1.0\" }\n")
    }

    @Test fun `ignores a plugins block inside pluginManagement`() {
      // Given a settings script
      val settingsScript =
        """
          pluginManagement {
            plugins {
              id("com.example.managed") version "1.0"
            }
          }

          plugins {
            id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
          }
        """.trimIndent()

      // When
      val adder = PluginAdder.of(settingsScript, listOf(ById("com.example.managed", "1.0")))

      // Then it is added to the script's own block, where it was not yet
      assertThat(adder.rewritten()).isEqualTo(
        """
          pluginManagement {
            plugins {
              id("com.example.managed") version "1.0"
            }
          }

          plugins {
            id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
            id("com.example.managed") version "1.0"
          }
        """.trimIndent()
      )
    }
  }

  @Nested inner class `when the script has no plugins block` {

    @Test fun `creates the block at the top of the script`() {
      // Given
      val buildScript =
        """
          // the app
          group = "com.example"

          dependencies {
            implementation("com.squareup.okio:okio:3.9.0")
          }
        """.trimIndent()

      // When
      val adder = PluginAdder.of(buildScript, listOf(ById("application"), ById("com.example.plugin", "1.0")))

      // Then
      assertThat(adder.rewritten()).isEqualTo(
        """
          // the app
          plugins {
            id("application")
            id("com.example.plugin") version "1.0"
          }

          group = "com.example"

          dependencies {
            implementation("com.squareup.okio:okio:3.9.0")
          }
        """.trimIndent()
      )
    }

    @Test fun `creates the block after the imports and the buildscript block`() {
      // Given
      val buildScript =
        """
          import java.time.Instant

          buildscript {
            repositories {
              mavenCentral()
            }
          }

          group = "com.example"
        """.trimIndent()

      // When
      val adder = PluginAdder.of(buildScript, listOf(ById("application")))

      // Then
      assertThat(adder.rewritten()).isEqualTo(
        """
          import java.time.Instant

          buildscript {
            repositories {
              mavenCentral()
            }
          }

          plugins {
            id("application")
          }

          group = "com.example"
        """.trimIndent()
      )
    }

    @Test fun `creates the block in an empty script`() {
      // The script had no trailing newline, so the result has none either.
      assertThat(PluginAdder.of("", listOf(ById("application"))).rewritten())
        .isEqualTo("plugins {\n  id(\"application\")\n}")
    }
  }

  @Nested inner class `reporting and rejecting` {

    @Test fun `reports which additions were added and which were skipped`() {
      val application = ById("application")
      val shadow = ByAlias("libs.plugins.shadow")

      val adder = PluginAdder.of("plugins {\n  application\n}\n", listOf(application, shadow))

      assertThat(adder.isChanged()).isTrue()
      assertThat(adder.added()).containsExactly(shadow)
      assertThat(adder.skipped()).containsExactly(application)
    }

    @Test fun `throws when the script does not parse`() {
      assertThatThrownBy { PluginAdder.of("plugins {\n  id(\"x\"\n}", listOf(ById("application"))).rewritten() }
        .isInstanceOf(KotlinParseException::class.java)
    }

    @Test fun `throws when a plugin id, a version or an accessor is malformed`() {
      listOf(ById(""), ById("com example"), ById("com.example.plugin", "1 .0"), ByAlias("shadow")).forEach { bad ->
        assertThatThrownBy { PluginAdder.of("plugins {}", listOf(bad)) }
          .describedAs("%s", bad)
          .isInstanceOf(IllegalArgumentException::class.java)
      }
    }

    @Test fun `throws when the same plugin is requested twice with different versions`() {
      assertThatThrownBy {
        PluginAdder.of("plugins {}", listOf(ById("com.example.plugin", "1.0"), ById("com.example.plugin", "2.0")))
      }
        .isInstanceOf(IllegalArgumentException::class.java)
        .hasMessageContaining("Conflicting additions")
    }
  }
}
