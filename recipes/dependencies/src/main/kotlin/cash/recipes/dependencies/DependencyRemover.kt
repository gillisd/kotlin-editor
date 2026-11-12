package cash.recipes.dependencies

import cash.grammar.kotlindsl.model.DependencyDeclaration
import cash.grammar.kotlindsl.parse.Mutator
import cash.grammar.kotlindsl.parse.Parser
import cash.grammar.kotlindsl.utils.Blocks.isDependencies
import cash.grammar.kotlindsl.utils.CollectingErrorListener
import cash.grammar.kotlindsl.utils.DependencyExtractor
import cash.recipes.dependencies.transform.Transform
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
 * Removes dependency declarations from the top-level `dependencies` blocks of a build script.
 *
 * A declaration is matched by `group:name` (whatever its version) or by accessor, on one configuration or on all of
 * them. A declaration on its own line is removed with that whole line, including an end-of-line comment; comment lines
 * above it stay. A declaration sharing its line with other statements is removed with its `;` separator only.
 */
public class DependencyRemover private constructor(
  private val removals: List<Removal>,
  input: CharStream,
  tokens: CommonTokenStream,
  errorListener: CollectingErrorListener,
) : Mutator(input, tokens, errorListener) {

  /**
   * One dependency to remove: [dependency] is `group:name` (no version), an accessor such as `libs.okhttp`, or, as a
   * string literal that starts with a colon, a project path such as `:core`. With no [configuration], it is removed
   * from every configuration.
   */
  public data class Removal @JvmOverloads constructor(
    public val dependency: Transform.Element,
    public val configuration: String? = null,
  ) {
    internal fun matches(declaration: DependencyDeclaration): Boolean =
      (configuration == null || configuration == declaration.configuration) &&
        withoutVersion(dependency.render()) == withoutVersion(declaration.identifier.path)
  }

  private val dependencyExtractor = DependencyExtractor(input, tokens, indent)
  private val matched = linkedSetOf<Removal>()

  // Statements alone on their line are deleted with the whole line. Statements sharing a line (a one-line block, or
  // `a(); b()`) are collected bare, then grouped into runs so each run drops exactly one `;` separator.
  private val lineDeletions = mutableListOf<IntRange>()
  private val inlineStatements = mutableListOf<IntRange>()

  /** The removals that matched at least one declaration. */
  public fun removed(): List<Removal> = removals.filter { it in matched }

  /** The removals that matched nothing. */
  public fun notFound(): List<Removal> = removals.filterNot { it in matched }

  override fun isChanged(): Boolean = lineDeletions.isNotEmpty() || inlineStatements.isNotEmpty()

  override fun enterNamedBlock(ctx: NamedBlockContext) {
    dependencyExtractor.onEnterBlock()
  }

  override fun exitNamedBlock(ctx: NamedBlockContext) {
    // The parent is a StatementContext whose parent is the script; nested blocks have a StatementsContext there.
    if (ctx.parent.parent is ScriptContext && ctx.isDependencies) {
      for (element in dependencyExtractor.collectDependencies(ctx).getDependencyDeclarationsWithContext()) {
        val removal = removals.firstOrNull { it.matches(element.declaration) } ?: continue
        matched += removal
        collect(element.statement)
      }
    }
    dependencyExtractor.onExitBlock()
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
    private val modulePattern = Regex("""[^\s:"]+:[^\s:"]+""")
    private val projectPathPattern = Regex("""(:[^\s:"]+)+""")
    private val accessorPattern = Regex("""[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+""")
    private val configurationPattern = Regex("""[A-Za-z_][A-Za-z0-9_]*""")

    /** `group:name` for a module version string (version dropped, quotes ignored), or the accessor itself. */
    private fun withoutVersion(rendered: String): String {
      val text = rendered.removeSurrounding("\"")
      if (text.startsWith(':')) return text // a project path is matched whole
      return if (':' in text) text.split(':').take(2).joinToString(":") else text
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
        removal.configuration?.let {
          require(configurationPattern.matches(it)) { "Invalid configuration name: '$it'" }
        }
        when (val dependency = removal.dependency) {
          is Transform.Element.StringLiteral -> require(
            modulePattern.matches(dependency.value) || projectPathPattern.matches(dependency.value)
          ) {
            "Invalid module: '${dependency.value}'. Expected 'group:name' (no version), " +
              "or a project path such as ':core'."
          }
          is Transform.Element.Accessor -> require(accessorPattern.matches(dependency.value)) {
            "Invalid accessor: '${dependency.value}'. Expected something like 'libs.foo.bar'."
          }
        }
      }
    }

    /**
     * Returns a [DependencyRemover], which eagerly parses [buildScript].
     *
     * @throws IllegalArgumentException if a removal is malformed, or if [DependencyExtractor] sees an expression it
     *   doesn't understand.
     * @throws IllegalStateException if [DependencyExtractor] sees an expression it doesn't understand.
     */
    @JvmStatic
    @Throws(IllegalStateException::class, IllegalArgumentException::class)
    public fun of(buildScript: Path, removals: List<Removal>): DependencyRemover =
      of(Parser.readOnlyInputStream(buildScript), removals)

    /**
     * Returns a [DependencyRemover], which eagerly parses [buildScript].
     *
     * @throws IllegalArgumentException if a removal is malformed, or if [DependencyExtractor] sees an expression it
     *   doesn't understand.
     * @throws IllegalStateException if [DependencyExtractor] sees an expression it doesn't understand.
     */
    @JvmStatic
    @Throws(IllegalStateException::class, IllegalArgumentException::class)
    public fun of(buildScript: String, removals: List<Removal>): DependencyRemover =
      of(buildScript.byteInputStream(), removals)

    private fun of(buildScript: InputStream, removals: List<Removal>): DependencyRemover {
      validate(removals)
      val errorListener = CollectingErrorListener()
      return Parser(
        file = buildScript,
        errorListener = errorListener,
        listenerFactory = { input, tokens, _ ->
          DependencyRemover(removals = removals, input = input, tokens = tokens, errorListener = errorListener)
        },
      ).listener()
    }
  }
}
