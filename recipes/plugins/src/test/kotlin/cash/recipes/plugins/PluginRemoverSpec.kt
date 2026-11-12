package cash.recipes.plugins

import cash.grammar.kotlindsl.parse.KotlinParseException
import cash.recipes.plugins.PluginRemover.Removal.ByAlias
import cash.recipes.plugins.PluginRemover.Removal.ById
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

internal class PluginRemoverSpec {

  private val everyForm =
    """
      plugins {
        application
        `java-library`
        // the language
        kotlin("jvm") version "2.2.0" // pinned
        id("com.example.plugin") version "1.0" apply false
        alias(libs.plugins.shadow)
      }

      dependencies {
        implementation("com.squareup.okio:okio:3.9.0")
      }
    """.trimIndent()

  @Nested inner class `removing an entry` {

    @Test fun `removes a plugin with a version, and its line`() {
      val remover = PluginRemover.of(everyForm, listOf(ById("com.example.plugin")))

      assertThat(remover.rewritten()).isEqualTo(
        """
          plugins {
            application
            `java-library`
            // the language
            kotlin("jvm") version "2.2.0" // pinned
            alias(libs.plugins.shadow)
          }

          dependencies {
            implementation("com.squareup.okio:okio:3.9.0")
          }
        """.trimIndent()
      )
    }

    @Test fun `removes a plugin written in any form, by its id`() {
      val remover = PluginRemover.of(
        everyForm,
        listOf(ById("application"), ById("java-library"), ById("org.jetbrains.kotlin.jvm")),
      )

      // The end-of-line comment goes with its entry; the comment line above stays.
      assertThat(remover.rewritten()).isEqualTo(
        """
          plugins {
            // the language
            id("com.example.plugin") version "1.0" apply false
            alias(libs.plugins.shadow)
          }

          dependencies {
            implementation("com.squareup.okio:okio:3.9.0")
          }
        """.trimIndent()
      )
    }

    @Test fun `removes an alias by its accessor`() {
      val remover = PluginRemover.of(everyForm, listOf(ByAlias("libs.plugins.shadow")))

      assertThat(remover.rewritten()).doesNotContain("alias(").contains("""id("com.example.plugin")""")
    }

    @Test fun `leaves an empty block when every entry is removed`() {
      assertThat(PluginRemover.of("plugins {\n  application\n}\n", listOf(ById("application"))).rewritten())
        .isEqualTo("plugins {\n}\n")
    }

    @Test fun `removes one entry of several on a line, with one separator`() {
      val oneLine = "plugins { application; id(\"com.example.plugin\") version \"1.0\"; `java-library` }\n"

      assertThat(PluginRemover.of(oneLine, listOf(ById("com.example.plugin"))).rewritten())
        .isEqualTo("plugins { application; `java-library` }\n")
      assertThat(PluginRemover.of(oneLine, listOf(ById("java-library"))).rewritten())
        .isEqualTo("plugins { application; id(\"com.example.plugin\") version \"1.0\" }\n")
    }
  }

  @Nested inner class `leaving things alone` {

    @Test fun `does not match an alias by a plugin id, or a plugin by an accessor`() {
      val remover = PluginRemover.of(everyForm, listOf(ById("libs.plugins.shadow"), ByAlias("com.example.plugin")))

      assertThat(remover.isChanged()).isFalse()
      assertThat(remover.rewritten()).isEqualTo(everyForm)
    }

    @Test fun `does not match the short name of a kotlin plugin`() {
      val remover = PluginRemover.of(everyForm, listOf(ById("jvm")))

      assertThat(remover.rewritten()).isEqualTo(everyForm)
      assertThat(remover.notFound()).containsExactly(ById("jvm"))
    }

    @Test fun `ignores a plugins block inside pluginManagement`() {
      val settingsScript =
        """
          pluginManagement {
            plugins {
              id("com.example.managed") version "1.0"
            }
          }

          plugins {
            id("com.example.managed")
          }
        """.trimIndent()

      assertThat(PluginRemover.of(settingsScript, listOf(ById("com.example.managed"))).rewritten()).isEqualTo(
        """
          pluginManagement {
            plugins {
              id("com.example.managed") version "1.0"
            }
          }

          plugins {
          }
        """.trimIndent()
      )
    }
  }

  @Nested inner class `reporting and rejecting` {

    @Test fun `reports which removals matched and which did not`() {
      val remover = PluginRemover.of(everyForm, listOf(ById("application"), ById("com.example.absent")))

      assertThat(remover.isChanged()).isTrue()
      assertThat(remover.removed()).containsExactly(ById("application"))
      assertThat(remover.notFound()).containsExactly(ById("com.example.absent"))
    }

    @Test fun `throws when the script does not parse`() {
      assertThatThrownBy { PluginRemover.of("plugins {\n  id(\"x\"\n}", listOf(ById("application"))).rewritten() }
        .isInstanceOf(KotlinParseException::class.java)
    }

    @Test fun `throws when a plugin id or an accessor is malformed`() {
      listOf(ById(""), ById("com example"), ByAlias("shadow")).forEach { bad ->
        assertThatThrownBy { PluginRemover.of("plugins {}", listOf(bad)) }
          .describedAs("%s", bad)
          .isInstanceOf(IllegalArgumentException::class.java)
      }
    }
  }
}
