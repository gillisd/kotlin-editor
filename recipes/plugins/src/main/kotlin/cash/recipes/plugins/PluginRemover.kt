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
import com.squareup.cash.grammar.KotlinParser.ScriptContext
import com.squareup.cash.grammar.KotlinParser.StatementContext
import org.antlr.v4.runtime.CharStream
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.Token
import java.io.InputStream
import java.nio.file.Path

/**
 * Removes entries from the top-level `plugins` block of a build script.
 *
 * A plugin is matched by id in whatever form it is written, with or without a version or `apply false`:
 * `kotlin("jvm")` counts as `org.jetbrains.kotlin.jvm`, and `application` and `` `java-library` `` count as their ids.
 * An `alias(…)` entry is matched by its accessor. An entry on its own line is removed with that whole line, including
 * an end-of-line comment; comment lines above it stay. An entry sharing its line with others is removed with its `;`
 * separator only. Unlike [PluginMutator], this does not require a normalized script.
 */
public class PluginRemover private constructor(
  private val removals: List<Removal>,
  input: CharStream,
  tokens: CommonTokenStream,
  errorListener: CollectingErrorListener,
) : Mutator(input, tokens, errorListener) {

  /** One entry to remove. */
  public sealed interface Removal {
    /** The plugin with this id, however it is written. */
    public data class ById(public val id: String) : Removal

    /** The `alias(…)` entry with this accessor, such as `libs.plugins.kotlin.jvm`. */
    public data class ByAlias(public val accessor: String) : Removal
  }

  private val matched = linkedSetOf<Removal>()

  // Statements alone on their line are deleted with the whole line. Statements sharing a line (a one-line block, or
  // `a; b`) are collected bare, then grouped into runs so each run drops exactly one `;` separator.
  private val lineDeletions = mutableListOf<IntRange>()
  private val inlineStatements = mutableListOf<IntRange>()

  /** The removals that matched an entry. */
  public fun removed(): List<Removal> = removals.filter { it in matched }

  /** The removals that matched nothing. */
  public fun notFound(): List<Removal> = removals.filterNot { it in matched }

  override fun isChanged(): Boolean = lineDeletions.isNotEmpty() || inlineStatements.isNotEmpty()

  override fun exitNamedBlock(ctx: NamedBlockContext) {
    // isPlugins only matches the script's own, top-level plugins block: not `pluginManagement { plugins { } }`.
    if (!ctx.isPlugins) return

    for (statement in ctx.statements()?.statement().orEmpty()) {
      val plugin = PluginExtractor.extractFromBlock(statement.leafRule()) ?: continue
      val removal = removals.firstOrNull { it.matches(plugin) } ?: continue
      matched += removal
      collect(statement)
    }
  }

  override fun exitScript(ctx: ScriptContext) {
    val inlineDeletions = runs(inlineStatements).map { withOneSeparator(it) }
    // The rewriter rejects overlapping deletes, so merge everything before deleting.
    mergeOverlapping(lineDeletions + inlineDeletions).forEach { range -> rewriter.delete(range.first, range.last) }
  }

  private fun collect(statement: StatementContext) {
    val lineStart = startOfLine(statement.start)
    val lineEnd = endOfLine(statement.stop)
    val aloneOnLine = (lineStart until statement.start.tokenIndex).all { isBlank(it) } &&
      (statement.stop.tokenIndex + 1 until lineEnd).all { isBlank(it) || isComment(it) }

    if (aloneOnLine) {
      // From the line's first token through its newline: indentation, the statement, any trailing comment.
      lineDeletions += lineStart..lineEnd
    } else {
      inlineStatements += statement.start.tokenIndex..statement.stop.tokenIndex
    }
  }

  /** Joins statements separated only by blanks and `;` into runs, e.g. the last two of `{ a; b; c }`. */
  private fun runs(statements: List<IntRange>): List<IntRange> =
    statements.sortedBy { it.first }.fold(mutableListOf()) { runs, range ->
      val last = runs.lastOrNull()
      if (last != null && (last.last + 1 until range.first).all { isBlank(it) || isSemicolon(it) }) {
        runs[runs.lastIndex] = last.first..range.last
      } else {
        runs += range
      }
      runs
    }

  /**
   * Extends a run to take one separator with it: the `;` after it (and the blanks after that), else the `;` before it,
   * else, when it was all the line held, the blanks after it.
   */
  private fun withOneSeparator(run: IntRange): IntRange {
    val after = nextNonBlank(run.last + 1)
    if (after != null && isSemicolon(after)) {
      return run.first..((nextNonBlank(after + 1) ?: (after + 1)) - 1)
    }
    val before = previousNonBlank(run.first - 1)
    if (before != null && isSemicolon(before)) {
      return before..run.last
    }
    return run.first..((nextNonBlank(run.last + 1) ?: (run.last + 1)) - 1)
  }

  private fun isSemicolon(index: Int): Boolean = tokens.get(index).type == KotlinLexer.SEMICOLON

  private fun isBlank(index: Int): Boolean = tokens.get(index).type == KotlinLexer.WS
  private fun isComment(index: Int): Boolean =
    tokens.get(index).type.let { it == KotlinLexer.LineComment || it == KotlinLexer.DelimitedComment }

  private fun nextNonBlank(from: Int): Int? = (from until tokens.size()).firstOrNull { !isBlank(it) }
  private fun previousNonBlank(from: Int): Int? = (from downTo 0).firstOrNull { !isBlank(it) }

  /** Index of the first token on [token]'s line. */
  private fun startOfLine(token: Token): Int {
    var i = token.tokenIndex - 1
    while (i >= 0 && tokens.get(i).type != KotlinLexer.NL) i--
    return i + 1
  }

  /** Index of the newline token (or EOF) ending [token]'s line. */
  private fun endOfLine(token: Token): Int {
    var i = token.tokenIndex + 1
    while (i < tokens.size() - 1 && tokens.get(i).type != KotlinLexer.NL) i++
    return i
  }

  public companion object {
    private val idPattern = Regex("""[A-Za-z0-9_][A-Za-z0-9_.\-]*""")
    private val accessorPattern = Regex("""[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+""")

    private fun Removal.matches(plugin: Plugin): Boolean = when (this) {
      is Removal.ByAlias -> plugin.type == Plugin.Type.BLOCK_ALIAS && plugin.id == accessor
      is Removal.ById -> when (plugin.type) {
        Plugin.Type.BLOCK_ALIAS -> false
        Plugin.Type.BLOCK_KOTLIN -> id == "org.jetbrains.kotlin.${plugin.id}"
        else -> id == plugin.id
      }
    }

    private fun mergeOverlapping(ranges: List<IntRange>): List<IntRange> =
      ranges.sortedBy { it.first }.fold(mutableListOf()) { merged, range ->
        val last = merged.lastOrNull()
        if (last != null && range.first <= last.last + 1) {
          merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
        } else {
          merged += range
        }
        merged
      }

    private fun validate(removals: List<Removal>) {
      for (removal in removals) {
        when (removal) {
          is Removal.ById -> require(idPattern.matches(removal.id)) { "Invalid plugin id: '${removal.id}'" }
          is Removal.ByAlias -> require(accessorPattern.matches(removal.accessor)) {
            "Invalid accessor: '${removal.accessor}'. Expected something like 'libs.plugins.foo'."
          }
        }
      }
    }

    /** Returns a [PluginRemover], which eagerly parses [buildScript]. */
    @JvmStatic
    @Throws(IllegalArgumentException::class)
    public fun of(buildScript: Path, removals: List<Removal>): PluginRemover =
      of(Parser.readOnlyInputStream(buildScript), removals)

    /** Returns a [PluginRemover], which eagerly parses [buildScript]. */
    @JvmStatic
    @Throws(IllegalArgumentException::class)
    public fun of(buildScript: String, removals: List<Removal>): PluginRemover =
      of(buildScript.byteInputStream(), removals)

    private fun of(buildScript: InputStream, removals: List<Removal>): PluginRemover {
      validate(removals)
      val errorListener = CollectingErrorListener()
      return Parser(
        file = buildScript,
        errorListener = errorListener,
        listenerFactory = { input, tokens, _ ->
          PluginRemover(removals = removals, input = input, tokens = tokens, errorListener = errorListener)
        },
      ).listener()
    }
  }
}
