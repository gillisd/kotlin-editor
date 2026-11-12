package cash.recipes.plugins

import cash.grammar.kotlindsl.model.Plugin
import cash.grammar.kotlindsl.parse.Mutator
import cash.grammar.kotlindsl.parse.Parser
import cash.grammar.kotlindsl.utils.Blocks.isPlugins
import cash.grammar.kotlindsl.utils.CollectingErrorListener
import cash.grammar.kotlindsl.utils.Context.leafRule
import cash.grammar.kotlindsl.utils.PluginExtractor
import com.squareup.cash.grammar.KotlinLexer
import com.squareup.cash.grammar.KotlinParser.ImportListContext
import com.squareup.cash.grammar.KotlinParser.NamedBlockContext
import com.squareup.cash.grammar.KotlinParser.ScriptContext
import org.antlr.v4.runtime.CharStream
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.Token
import java.io.InputStream
import java.nio.file.Path

/**
 * Adds entries to the top-level `plugins` block of a build script, after its last entry: `id("x") version "1.0"`,
 * `id("x")` or `alias(libs.plugins.x)`.
 *
 * A plugin that is already in the block, in any form, is skipped: `kotlin("jvm")` counts as
 * `org.jetbrains.kotlin.jvm`, and `application` and `` `java-library` `` count as their ids. A script with no
 * `plugins` block gets one after its imports and `buildscript` block, else at the top. Unlike [PluginMutator], this
 * does not require a normalized script.
 */
public class PluginAdder private constructor(
  private val additions: List<Addition>,
  input: CharStream,
  tokens: CommonTokenStream,
  errorListener: CollectingErrorListener,
) : Mutator(input, tokens, errorListener) {

  /** One entry to add. */
  public sealed interface Addition {
    /** The entry as it will be written, without indentation. */
    public fun render(): String

    /** `id("…")`, with `version "…"` when [version] is given. */
    public data class ById @JvmOverloads constructor(
      public val id: String,
      public val version: String? = null,
    ) : Addition {
      override fun render(): String = if (version == null) "id(\"$id\")" else "id(\"$id\") version \"$version\""
    }

    /** `alias(…)`, for an accessor such as `libs.plugins.kotlin.jvm`. */
    public data class ByAlias(public val accessor: String) : Addition {
      override fun render(): String = "alias($accessor)"
    }
  }

  private val added = mutableListOf<Addition>()
  private val skipped = mutableListOf<Addition>()
  private var handledBlock = false

  // What a new plugins block must come after: the imports and a top-level buildscript or pluginManagement block.
  private var preamble: Token? = null

  /** The additions that were inserted, in the order given. */
  public fun added(): List<Addition> = added.toList()

  /** The additions that were not inserted because the plugin is already in the block. */
  public fun skipped(): List<Addition> = skipped.toList()

  override fun isChanged(): Boolean = added.isNotEmpty()

  override fun exitImportList(ctx: ImportListContext) {
    if (ctx.importHeader().isNotEmpty()) preamble = ctx.importHeader().last().stop
  }

  override fun exitNamedBlock(ctx: NamedBlockContext) {
    if (ctx.isPlugins && !handledBlock) {
      handledBlock = true
      addTo(ctx)
    } else if (ctx.parent.parent is ScriptContext && ctx.name().text in setOf("buildscript", "pluginManagement")) {
      preamble = ctx.stop
    }
  }

  override fun exitScript(ctx: ScriptContext) {
    if (!handledBlock) addNewBlock(ctx)
  }

  /** Records each addition as added or skipped (already in [present]), and returns the ones to insert. */
  private fun selectNew(present: MutableSet<String>): List<Addition> {
    val toAdd = mutableListOf<Addition>()
    for (addition in additions) {
      if (present.add(addition.key())) toAdd += addition else skipped += addition
    }
    added += toAdd
    return toAdd
  }

  private fun addTo(block: NamedBlockContext) {
    val statements = block.statements()?.statement().orEmpty()
    val present = statements
      .mapNotNull { PluginExtractor.extractFromBlock(it.leafRule()) }
      .mapTo(mutableSetOf()) { it.key() }
    val lines = selectNew(present).map { it.render() }
    if (lines.isEmpty()) return

    val last = statements.lastOrNull()
    when {
      last == null -> insertIntoEmptyBlock(block, lines)
      // The closing brace shares the last entry's line, as in `plugins { application }`: stay on that line.
      endOfLine(last.stop).tokenIndex > block.stop.tokenIndex ->
        rewriter.insertAfter(last.stop, lines.joinToString(separator = "") { "; $it" })
      else -> rewriter.insertBefore(endOfLine(last.stop), lines.joinToString(separator = "") { "\n$indent$it" })
    }
  }

  private fun addNewBlock(script: ScriptContext) {
    val lines = selectNew(mutableSetOf()).map { it.render() }
    if (lines.isEmpty()) return

    val block = "plugins {\n" + lines.joinToString(separator = "") { "$indent$it\n" } + "}"
    val after = preamble
    when {
      after != null -> rewriter.insertBefore(endOfLine(after), "\n\n$block")
      script.statement().isEmpty() -> rewriter.insertBefore(script.start, "$block\n")
      else -> rewriter.insertBefore(script.statement().first().start, "$block\n\n")
    }
  }

  private fun insertIntoEmptyBlock(block: NamedBlockContext, lines: List<String>) {
    val closingBrace = block.stop
    val body = lines.joinToString(separator = "") { "$indent$it\n" }
    if (closingBrace.line == block.start.line) {
      rewriter.insertBefore(closingBrace, "\n$body") // `plugins {}` on one line: open it up
    } else {
      rewriter.insertBefore(closingBrace, body)
    }
  }

  /** The newline token ending the line that [token] is on (or EOF), so an insert lands after a trailing comment. */
  private fun endOfLine(token: Token): Token {
    var i = token.tokenIndex + 1
    while (i < tokens.size()) {
      val t = tokens.get(i)
      if (t.type == KotlinLexer.NL || t.type == Token.EOF) return t
      i++
    }
    return tokens.get(tokens.size() - 1)
  }

  public companion object {
    private val idPattern = Regex("""[A-Za-z0-9_][A-Za-z0-9_.\-]*""")
    private val versionPattern = Regex("""[^\s"${'$'}\\]+""")
    private val accessorPattern = Regex("""[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+""")

    private fun Addition.key(): String = when (this) {
      is Addition.ById -> "id|$id"
      is Addition.ByAlias -> "alias|$accessor"
    }

    /** Plugins are compared by id whatever form they are written in; aliases by their accessor. */
    private fun Plugin.key(): String = when (type) {
      Plugin.Type.BLOCK_ALIAS -> "alias|$id"
      Plugin.Type.BLOCK_KOTLIN -> "id|org.jetbrains.kotlin.$id"
      else -> "id|$id"
    }

    private fun validate(additions: List<Addition>) {
      for (addition in additions) {
        when (addition) {
          is Addition.ById -> {
            require(idPattern.matches(addition.id)) { "Invalid plugin id: '${addition.id}'" }
            addition.version?.let {
              require(versionPattern.matches(it)) { "Invalid version for '${addition.id}': '$it'" }
            }
          }
          is Addition.ByAlias -> require(accessorPattern.matches(addition.accessor)) {
            "Invalid accessor: '${addition.accessor}'. Expected something like 'libs.plugins.foo'."
          }
        }
      }
      additions.filterIsInstance<Addition.ById>().groupBy { it.id }.values
        .filter { sameId -> sameId.distinct().size > 1 }
        .forEach { conflicting ->
          throw IllegalArgumentException(
            "Conflicting additions for the same plugin: ${conflicting.joinToString { it.render() }}"
          )
        }
    }

    /** Returns a [PluginAdder], which eagerly parses [buildScript]. */
    @JvmStatic
    @Throws(IllegalArgumentException::class)
    public fun of(buildScript: Path, additions: List<Addition>): PluginAdder =
      of(Parser.readOnlyInputStream(buildScript), additions)

    /** Returns a [PluginAdder], which eagerly parses [buildScript]. */
    @JvmStatic
    @Throws(IllegalArgumentException::class)
    public fun of(buildScript: String, additions: List<Addition>): PluginAdder =
      of(buildScript.byteInputStream(), additions)

    private fun of(buildScript: InputStream, additions: List<Addition>): PluginAdder {
      validate(additions)
      val errorListener = CollectingErrorListener()
      return Parser(
        file = buildScript,
        errorListener = errorListener,
        listenerFactory = { input, tokens, _ ->
          PluginAdder(additions = additions, input = input, tokens = tokens, errorListener = errorListener)
        },
      ).listener()
    }
  }
}
