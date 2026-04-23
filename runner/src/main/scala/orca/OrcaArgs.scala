package orca

import mainargs.{Flag, ParserForClass, arg}

/** Parsed command-line arguments for the `orca` entry point. */
case class OrcaArgs(
    @arg(positional = true, doc = "task description")
    userPrompt: String = "",
    @arg(doc = "verbose logging")
    verbose: Flag = Flag()
)

object OrcaArgs:
  given ParserForClass[OrcaArgs] = ParserForClass[OrcaArgs]

  /** Parse the given argv or return a human-readable error. */
  def parse(args: Seq[String]): Either[String, OrcaArgs] =
    summon[ParserForClass[OrcaArgs]].constructEither(args.toList)

  /** Convenience for flow scripts:
    *
    * ```
    * flowWith(args = OrcaArgs.from(args)):
    *   // userPrompt resolves against the positional CLI arg
    * ```
    *
    * Throws `OrcaFlowException` on a parse failure — flow scripts should
    * either surface the message to the user or catch and handle it.
    */
  def from(args: Seq[String]): OrcaArgs =
    parse(args) match
      case Right(parsed)  => parsed
      case Left(errorMsg) => throw OrcaFlowException(errorMsg)
