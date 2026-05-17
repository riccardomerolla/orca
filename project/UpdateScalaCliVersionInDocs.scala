import sbt.*

import java.util.regex.{Matcher, Pattern}

/** Bumps scala-cli `using dep` coordinates in docs as part of a release.
  *
  * Matches `<organization>::<module>:<version>` — the form Orca's flow scripts
  * and READMEs use. Complement to the stock
  * `com.softwaremill.UpdateVersionInDocs`, which is hardcoded to the sbt build
  * coordinate `"org" %% "name" % "version"`; the two are typically chained
  * from the `updateDocs` task so either form gets bumped.
  *
  * Each entry in `filesToUpdate` is either a file or a directory; directories
  * are walked recursively and `.md` / `.sc` files are rewritten in place.
  * Returns the files that actually changed so the release driver can stage
  * them with `git add`.
  */
object UpdateScalaCliVersionInDocs {
  def apply(
      log: Logger,
      organization: String,
      version: String,
      filesToUpdate: List[File]
  ): Seq[File] = {
    val orgQuoted = Pattern.quote(organization)
    val versionRegex = s"""($orgQuoted::[\\w-]+:)([\\w\\.-]+)""".r

    def rewrite(f: File): Option[File] = {
      val before = IO.read(f)
      val after = versionRegex.replaceAllIn(
        before,
        m => Matcher.quoteReplacement(m.group(1) + version)
      )
      if (after != before) {
        log.info(
          s"[UpdateScalaCliVersionInDocs] Bumped versions in ${f.getPath} → $version"
        )
        IO.write(f, after)
        Some(f)
      } else None
    }

    def walk(f: File): Seq[File] =
      if (f.isDirectory)
        Option(f.listFiles).toSeq.flatten.flatMap(walk)
      else if (
        f.isFile && (f.getName.endsWith(".md") || f.getName.endsWith(".sc"))
      )
        rewrite(f).toSeq
      else Seq.empty

    filesToUpdate.flatMap { f =>
      if (!f.exists()) {
        log.warn(
          s"[UpdateScalaCliVersionInDocs] ${f.getPath} does not exist, skipping"
        )
        Seq.empty
      } else walk(f)
    }
  }
}
