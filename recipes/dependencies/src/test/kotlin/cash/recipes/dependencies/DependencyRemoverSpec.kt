package cash.recipes.dependencies

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

// Each spec stays @Disabled("pending") until it is written; remove the marker as it is filled in.
internal class DependencyRemoverSpec {

  @Nested inner class `removing from the dependencies block` {

    @Disabled("pending") @Test fun `removes a string-literal declaration matched by group and name, whatever its version`() {}

    @Disabled("pending") @Test fun `removes a version catalog accessor declaration`() {}

    @Disabled("pending") @Test fun `removes a declaration with a trailing lambda, lambda included`() {}

    @Disabled("pending") @Test fun `removes only from the given configuration, leaving the same dependency on others`() {}

    @Disabled("pending") @Test fun `removes from every configuration when no configuration is given`() {}

    @Disabled("pending") @Test fun `removes several declarations in one pass`() {}

    @Disabled("pending") @Test fun `ignores dependencies blocks that are not top-level, such as buildscript or a task`() {}
  }

  @Nested inner class `keeping the rest of the file intact` {

    @Disabled("pending") @Test fun `removes the whole line, leaving no blank line behind`() {}

    @Disabled("pending") @Test fun `removes an end-of-line comment with its declaration but keeps comment lines above it`() {}

    @Disabled("pending") @Test fun `leaves an empty dependencies block when the last declaration is removed`() {}

    @Disabled("pending") @Test fun `removes from a one-line dependencies block`() {}
  }

  @Nested inner class `reporting what changed` {

    @Disabled("pending") @Test fun `reports no change and returns the script byte-for-byte when nothing matches`() {}

    @Disabled("pending") @Test fun `reports which removals matched and which were not found`() {}
  }

  @Nested inner class `rejecting bad input` {

    @Disabled("pending") @Test fun `throws when the script does not parse`() {}

    @Disabled("pending") @Test fun `throws when coordinates are not group-colon-name`() {}
  }
}
