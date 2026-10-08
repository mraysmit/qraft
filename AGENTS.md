# Qraft project memory and instructions

## Mandatory logging policy

Recorded on 2026-10-08 at the user's explicit request.

- Treat the logging remediation as complete. Any logged error without clear, explicit intentional attribution is a legitimate error requiring investigation and remediation, even when tests pass or Maven exits successfully.
- Intentional test errors must carry `*** INTENTIONAL ERROR: <entry>, caused by <test> ***` or `*** INJECTED FAILURE: <entry>, injected by <test> ***`, with a specific matching failure definition and the responsible test identified. A flag on an exception header covers its following stack trace; individual frames and cause lines do not need repeated flags.
- Do not dismiss an unflagged error as normal test noise, a harmless negative test, or an assumed remaining logging gap. Establish its cause and fix it. If evidence proves a test deliberately causes the failure, make that intention explicit through the specific declaration or injected marker and audit it before accepting the run.
- Do not hide errors by filtering, suppressing, lowering their severity, or broadening expectations to excuse unrelated failures. Preserve messages and stack traces.
- Verify the retained Maven output, application logs, helper-subprocess logs, and Docker archives. Test totals and exit codes alone are insufficient; every unflagged error requires remediation.
- Follow [docs/TESTING.md](docs/TESTING.md#intentional-error-flags-and-log-auditing) for flagging and audit rules, and its existing visible VS Code terminal and log-capture workflow for verification.
