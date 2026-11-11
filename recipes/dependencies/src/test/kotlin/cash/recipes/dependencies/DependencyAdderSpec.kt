package cash.recipes.dependencies

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

// Each spec stays @Disabled("pending") until it is written; remove the marker as it is filled in.
internal class DependencyAdderSpec {

  @Nested inner class `adding to an existing dependencies block` {

    @Disabled("pending") @Test fun `adds a string-literal dependency at the end of the block`() {}

    @Disabled("pending") @Test fun `adds a version catalog accessor dependency`() {}

    @Disabled("pending") @Test fun `adds after the last declaration with the same configuration`() {}

    @Disabled("pending") @Test fun `adds several dependencies in the order given`() {}

    @Disabled("pending") @Test fun `wraps the dependency in platform() or testFixtures() when a capability is given`() {}

    @Disabled("pending") @Test fun `adds to an empty multi-line dependencies block`() {}

    @Disabled("pending") @Test fun `adds to an empty one-line dependencies block`() {}

    @Disabled("pending") @Test fun `matches the indentation already used in the file`() {}

    @Disabled("pending") @Test fun `keeps a comment just before the closing brace above the closing brace`() {}

    @Disabled("pending") @Test fun `leaves existing declarations, their trailing lambdas and comments untouched`() {}

    @Disabled("pending") @Test fun `ignores dependencies blocks that are not top-level, such as buildscript or a task`() {}
  }

  @Nested inner class `when the script has no dependencies block` {

    @Disabled("pending") @Test fun `creates a dependencies block at the end of the script`() {}

    @Disabled("pending") @Test fun `creates the block after the plugins and repositories blocks`() {}
  }

  @Nested inner class `when the dependency is already declared` {

    @Disabled("pending") @Test fun `skips a dependency already declared with the same version`() {}

    @Disabled("pending") @Test fun `skips a dependency already declared with a different version`() {}

    @Disabled("pending") @Test fun `still adds the same coordinates on a different configuration`() {}

    @Disabled("pending") @Test fun `recognises an already declared version catalog accessor`() {}
  }

  @Nested inner class `reporting what changed` {

    @Disabled("pending") @Test fun `reports no change and returns the script byte-for-byte when nothing is added`() {}

    @Disabled("pending") @Test fun `reports which dependencies were added and which were skipped`() {}

    @Disabled("pending") @Test fun `reports the catalog accessors it used so the version catalog can be updated`() {}
  }

  @Nested inner class `rejecting bad input` {

    @Disabled("pending") @Test fun `throws when the script does not parse`() {}

    @Disabled("pending") @Test fun `throws when coordinates are not group-colon-name with an optional version`() {}

    @Disabled("pending") @Test fun `throws when the same dependency is requested twice with different versions`() {}
  }
}
