package cash.recipes.plugins

import cash.grammar.kotlindsl.model.Plugin
import cash.grammar.kotlindsl.parse.Mutator
import cash.grammar.kotlindsl.parse.Parser
import cash.grammar.kotlindsl.utils.Blocks.isPlugins
import cash.grammar.kotlindsl.utils.CollectingErrorListener
import cash.grammar.kotlindsl.utils.Context.leafRule
import cash.grammar.kotlindsl.utils.PluginExtractor
import com.squareup.cash.grammar.KotlinLexer
import com.squareup.cash.grammar.KotlinParser.NamedBlockContext
import com.squareup.cash.grammar.KotlinParser.StatementContext
import org.antlr.v4.runtime.CharStream
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.Token
import java.io.InputStream
import java.nio.file.Path

/**
 * Changes the versions of plugins in the top-level `plugins` block of a build script, such as
 * `id("com.example.plugin") version "1.0"` to `"2.0"`.
 *
 * Plugins are addressed by id; a Kotlin plugin such as `kotlin("jvm")` can also be addressed by its full id,
 * `org.jetbrains.kotlin.jvm`. Only a plain string version is changed. A plugin with no version, a version that is not a
 * string literal (a variable, a template), or one applied through a version catalog alias is left alone and reported in
 * [skipped] with the reason. Unlike [PluginMutator], this does not require a normalized script.
 */
public class PluginVersionMutator private constructor(
  private val versions: Map<String, String>,
  input: CharStream,
  tokens: CommonTokenStream,
  errorListener: CollectingErrorListener,
) : Mutator(input, tokens, errorListener) {

  private val changed = linkedSetOf<String>()
  private val skipped = linkedMapOf<String, String>()
  private val found = mutableSetOf<String>()

  /** The plugin ids whose version was changed. */
  public fun changed(): Set<String> = changed.toSet()

  /** The plugin ids that were found but not changed, each with the reason. */
  public fun skipped(): Map<String, String> = skipped.toMap()

  /** The plugin ids that are not applied in the top-level `plugins` block. */
  public fun notFound(): Set<String> = versions.keys - found

  override fun isChanged(): Boolean = changed.isNotEmpty()

  override fun exitNamedBlock(ctx: NamedBlockContext) {
    // isPlugins only matches the script's own, top-level plugins block: not `pluginManagement { plugins { } }`.
    if (!ctx.isPlugins) return

    for (statement in ctx.statements()?.statement().orEmpty()) {
      val plugin = PluginExtractor.extractFromBlock(statement.leafRule()) ?: continue
      val id = idsOf(plugin).firstOrNull { it in versions } ?: continue
      val wanted = versions.getValue(id)
      found += id

      if (plugin.type == Plugin.Type.BLOCK_ALIAS) {
        skipped[id] = "applied through a version catalog alias; its version is in the catalog"
        continue
      }
      val version = versionOf(statement)
      when {
        version == null -> skipped[id] = "no version declared"
        version.literal == null -> skipped[id] = "version is not a string literal"
        version.literal == wanted -> skipped[id] = "already at $wanted"
        else -> {
          rewriter.replace(version.open, version.close, "\"$wanted\"")
          changed += id
        }
      }
    }
  }

  /** The ids a plugin can be addressed by: its own id, plus the full id for `kotlin("…")` plugins. */
  private fun idsOf(plugin: Plugin): List<String> =
    if (plugin.type == Plugin.Type.BLOCK_KOTLIN) {
      listOf(plugin.id, "org.jetbrains.kotlin.${plugin.id}")
    } else {
      listOf(plugin.id)
    }

  /** Where a statement's `version …` argument sits, and its text when it is a plain string literal. */
  private class Version(val open: Token, val close: Token, val literal: String?)

  private fun versionOf(statement: StatementContext): Version? {
    val first = statement.start.tokenIndex
    val last = statement.stop.tokenIndex
    val keyword = (first..last).firstOrNull {
      tokens.get(it).type == KotlinLexer.Identifier && tokens.get(it).text == "version"
    } ?: return null

    val argument = (keyword + 1..last).firstOrNull { tokens.get(it).type != KotlinLexer.WS } ?: return null
    if (tokens.get(argument).type != KotlinLexer.QUOTE_OPEN) {
      return Version(tokens.get(argument), tokens.get(argument), literal = null)
    }

    // A plain literal is QUOTE_OPEN, then only text parts, then QUOTE_CLOSE. Anything else (`$x`, `${…}`) isn't.
    var i = argument + 1
    val text = StringBuilder()
    while (i <= last && tokens.get(i).type == KotlinLexer.LineStrText) {
      text.append(tokens.get(i).text)
      i++
    }
    return if (i <= last && tokens.get(i).type == KotlinLexer.QUOTE_CLOSE) {
      Version(tokens.get(argument), tokens.get(i), literal = text.toString())
    } else {
      Version(tokens.get(argument), tokens.get(argument), literal = null)
    }
  }

  public companion object {
    private val idPattern = Regex("""[A-Za-z0-9_][A-Za-z0-9_.\-]*""")
    private val versionPattern = Regex("""[^\s"${'$'}\\]+""")

    private fun validate(versions: Map<String, String>) {
      for ((id, version) in versions) {
        require(idPattern.matches(id)) { "Invalid plugin id: '$id'" }
        require(versionPattern.matches(version)) { "Invalid version for '$id': '$version'" }
      }
    }

    /** Returns a [PluginVersionMutator], which eagerly parses [buildScript]. [versions] maps plugin id to new version. */
    @JvmStatic
    @Throws(IllegalArgumentException::class)
    public fun of(buildScript: Path, versions: Map<String, String>): PluginVersionMutator =
      of(Parser.readOnlyInputStream(buildScript), versions)

    /** Returns a [PluginVersionMutator], which eagerly parses [buildScript]. [versions] maps plugin id to new version. */
    @JvmStatic
    @Throws(IllegalArgumentException::class)
    public fun of(buildScript: String, versions: Map<String, String>): PluginVersionMutator =
      of(buildScript.byteInputStream(), versions)

    private fun of(buildScript: InputStream, versions: Map<String, String>): PluginVersionMutator {
      validate(versions)
      val errorListener = CollectingErrorListener()
      return Parser(
        file = buildScript,
        errorListener = errorListener,
        listenerFactory = { input, tokens, _ ->
          PluginVersionMutator(versions = versions, input = input, tokens = tokens, errorListener = errorListener)
        },
      ).listener()
    }
  }
}
