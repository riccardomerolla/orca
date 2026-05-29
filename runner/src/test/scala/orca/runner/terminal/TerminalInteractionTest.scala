package orca.runner.terminal

import java.nio.charset.StandardCharsets.UTF_8

class TerminalInteractionTest extends munit.FunSuite:

  test(
    "default output stream encodes UTF-8 regardless of the JVM launch locale"
  ):
    // Regression: under a non-UTF-8 locale (C/POSIX — common in containers and
    // editor sandboxes) the JVM resolves System.err to US-ASCII and every glyph
    // (…, ✖, ▸, ●) encodes to '?'. The default stream must force UTF-8.
    assertEquals(TerminalInteraction.utf8Stderr.charset(), UTF_8)
