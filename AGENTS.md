# AGENTS.md

## Project Purpose

This repository is a learning laboratory for studying observability concepts
through small, focused proof-of-concepts.

The goal is to understand how observability mechanisms work internally before
using higher-level abstractions or automatic instrumentation.

Learning and understanding are more important than production-ready architecture.

## General Development Guidelines

- Keep implementations concise, objective, and focused on the current learning goal.
- Prefer the smallest implementation that demonstrates the concept being studied.
- Respect the existing project architecture.
- Do not introduce unnecessary abstractions.
- Do not add code comments unless explicitly requested.
- Prefer clean and readable code over clever solutions.
- Do not add dependencies unless:
    - explicitly requested; or
    - they significantly simplify the implementation.
- When proposing an optional dependency, explain what it provides and why it is useful before adding it.
- Do not add AI authorship, co-authorship, generated-by metadata, or similar references to commits or source files.

## Architecture

Before making structural changes:

1. Inspect the existing project structure.
2. Follow the architecture and conventions already present.
3. Do not redesign working code without a concrete reason.
4. If no architecture or convention exists and a structural decision is necessary, ask before introducing one.

## Testing

Always validate implementations using the testing conventions already established by the project.

Before adding tests:

1. Inspect the project for existing test patterns and libraries.
2. Follow those conventions when they exist.
3. If no testing convention or testing library has been established, ask which approach should be used before introducing one.

Do not introduce a testing framework implicitly.

## Learning Workflow

For each new concept:

1. Explain the concept and its purpose.
2. Identify where it fits in the current application.
3. Implement the smallest useful example.
4. Validate the implementation.
5. Explain what can be observed from the result.
6. Update the POC's `CONTEXT.md` when a meaningful milestone has changed.

Do not implement multiple future learning steps at once.

Prefer:

explicit behavior → understanding → abstraction

over:

abstraction → hidden behavior

## OpenTelemetry Learning Guidelines

During the initial OpenTelemetry POCs, prefer explicit/manual instrumentation.

Unless explicitly requested, do not introduce:

- OpenTelemetry Java Agent
- Micrometer Tracing
- automatic Spring/framework instrumentation
- wrappers that hide `Tracer`, `Span`, `Scope`, or `Context`

These abstractions may be introduced later specifically to compare manual and automatic instrumentation.

## Context Management

Each POC should contain a `CONTEXT.md`.

`CONTEXT.md` represents the current state of that POC and should allow a future session to continue without relying on previous conversation history.

After a meaningful milestone:

- update `Current Progress`;
- update `Completed Milestones`;
- update `Current Focus`;
- record important technical decisions or concepts learned when relevant.

Do not update `CONTEXT.md` for trivial questions or exploratory discussion.

Do not mark a milestone as complete unless the implementation was actually created and validated.

When information becomes outdated, rewrite it instead of continuously appending historical notes.

Keep `CONTEXT.md` concise.

## Starting or Resuming Work

When starting or resuming work on a POC:

1. Read its `CONTEXT.md`.
2. Inspect the repository to verify that the documented state matches the code.
3. Treat the repository as the source of truth if documentation and implementation disagree.
4. Continue from `Current Focus`.

## Finishing a Task

After a code-changing task, summarize:

- what was implemented;
- how it was validated;
- whether `CONTEXT.md` was updated;
- the next recommended learning step.

Do not automatically implement the next learning step.