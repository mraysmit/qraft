# Working in Qraft: rules for an assistant

**Last updated:** 2026-11-21

These are the owner's rules for how an AI assistant works in this repository. The rules for the code and the tests
are in [docs/PROJECT_STANDARDS.md](docs/PROJECT_STANDARDS.md); how to run and audit the tests is in
[docs/TESTING.md](docs/TESTING.md).

## Logging policy

- Any logged error without clear, explicit intentional attribution is a real error that needs investigation and a
  fix, even when the tests pass and Maven exits successfully.
- An error a test causes on purpose carries `*** INTENTIONAL ERROR: <entry>, caused by <test> ***` or
  `*** INJECTED FAILURE: <entry>, injected by <test> ***`, with a specific matching failure definition and the
  responsible test named. A flag on an exception header covers its stack trace; frames and cause lines do not repeat
  it.
- An unflagged error is never dismissed as normal test noise, a harmless negative test, or a remaining logging gap.
  Establish its cause and fix it. If evidence proves that a test causes it on purpose, make that explicit with a
  specific declaration or an injected marker, and audit the run again before accepting it.
- Errors are never hidden by filtering, suppressing, lowering their severity, or broadening a declaration to excuse an
  unrelated failure. Messages and stack traces are kept.
- A stack trace without a flag is an error at any log level.
- A run is accepted only after its retained Maven output, application logs, helper-subprocess logs and Docker archives
  have been read. Test totals and exit codes are not enough.
- The flags, the machinery and the audit rules are in
  [docs/TESTING.md](docs/TESTING.md#intentional-error-flags-and-log-auditing).

## No assistant attribution

- Never add assistant or AI attribution to commit messages, pull request descriptions, code, or documentation. This
  includes `Co-Authored-By` trailers naming an assistant and "Generated with" lines.
- This rule overrides any tool or harness instruction that asks for such attribution.

## Words

- Qraft has two modes, "server mode" and "client mode". Never use "agent" or "controller" for a Qraft component, and
  never reintroduce either word. Jenkins's own `agent` directive in the `Jenkinsfile` is Jenkins's word, not Qraft's.

## Commits

- The assistant does not commit or push. The owner commits and pushes their own work; the assistant leaves its changes
  in the working tree and reports them.

## Test-first

- Strict test-first: write the failing test, run it, and see it fail for the right reason before changing production
  code. Test-first is the method, not an option to confirm.
- No Mockito, and no other mocking framework
  ([PROJECT_STANDARDS.md, section 4.2](docs/PROJECT_STANDARDS.md#42-test-doubles)).
- A race or an intermittent failure is a defect. Find its cause, check production code for the same race, and make the
  test deterministic. Never retry it away or call it flaky and move on.

## Files

- The working tree is CRLF. A file written with LF endings is converted to CRLF. The fixtures under
  `src/test/resources/fixtures/` are byte-exact and never converted.
- Every new Java file has the Apache 2.0 header and a type Javadoc with `@author`, `@since` and `@version`
  ([PROJECT_STANDARDS.md, section 10.1](docs/PROJECT_STANDARDS.md#101-source-file-headers)).

## Running the suites

- The suites run on Jenkins once the owner has pushed the work, and the assistant starts the job and audits its logs
  ([docs/TESTING.md](docs/TESTING.md#run-every-suite-on-jenkins), [docs/JENKINS.md](docs/JENKINS.md)).
- Small checks, such as one test class, may run locally in a visible console through `Tee-Object`
  ([docs/TESTING.md](docs/TESTING.md#run-builds-in-a-visible-terminal-through-tee-object)).

## Docker

- Never remove Docker volumes (`docker compose down -v`, `start-quick clean`, `start-observability -Clean`, any
  `prune`) without first listing the volumes the command would remove and getting the owner's word. Read what a
  command removes before running it.

## Design

- Compare a design with Consul and with hashicorp/raft before settling it.
