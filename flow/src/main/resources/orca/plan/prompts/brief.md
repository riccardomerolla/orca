The plan we just produced will be implemented by separate coding agents, each
starting from an empty context — they have NOT seen your exploration of this
codebase. Write a single briefing they can rely on so they don't have to
rediscover it.

Include, as concise notes: the modules and directories involved and what each is
responsible for; the specific files (with paths) they will read or change, and
why; the key types, functions, and APIs they will build on, with signatures; the
conventions to follow (error handling, naming, testing, build); and anything
non-obvious you learned that would otherwise cost a re-read.

Do NOT restate the tasks or the plan — the agents already have those. Output only
the briefing, as plain markdown.
