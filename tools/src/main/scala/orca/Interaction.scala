package orca

trait Interaction:
  def listeners: List[OrcaListener]
  def runInteractive(handle: InteractiveHandle[?]): Unit
