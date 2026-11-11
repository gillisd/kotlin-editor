package cash.recipes.plugins

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

// Each spec stays @Disabled("pending") until it is written; remove the marker as it is filled in.
internal class PluginVersionMutatorSpec {

  @Nested inner class `changing versions` {

    @Disabled("pending") @Test fun `changes the version of an id plugin`() {}

    @Disabled("pending") @Test fun `changes the version of a kotlin plugin addressed by its full id`() {}

    @Disabled("pending") @Test fun `keeps apply false after the version`() {}

    @Disabled("pending") @Test fun `changes several plugins in one pass`() {}

    @Disabled("pending") @Test fun `ignores plugins blocks that are not top-level, such as pluginManagement`() {}
  }

  @Nested inner class `leaving things alone` {

    @Disabled("pending") @Test fun `skips a plugin declared without a version`() {}

    @Disabled("pending") @Test fun `skips a plugin applied through a version catalog alias`() {}

    @Disabled("pending") @Test fun `skips a version that is not a string literal`() {}

    @Disabled("pending") @Test fun `leaves the rest of the file byte-for-byte`() {}
  }

  @Nested inner class `reporting what changed` {

    @Disabled("pending") @Test fun `reports no change and returns the script byte-for-byte when already at that version`() {}

    @Disabled("pending") @Test fun `reports which plugins changed, which were skipped and why, and which were not found`() {}
  }

  @Nested inner class `rejecting bad input` {

    @Disabled("pending") @Test fun `throws when the script does not parse`() {}

    @Disabled("pending") @Test fun `throws when a plugin id or version is malformed`() {}
  }
}
