package orca

trait AgentInput[A]:
  def serialize(a: A): String
