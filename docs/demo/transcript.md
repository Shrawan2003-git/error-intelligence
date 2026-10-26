# Error Intelligence — narrated product walkthrough

All app data shown is synthetic. The optional AI worker is disabled in this local demo.

## 1. Error Intelligence

Meet Error Intelligence, a Java and React application for engineering teams. It helps a team see repeated application failures, investigate them together, and keep a record of what actually fixed them. This tour uses invented errors and a local test workspace.

## 2. Errors are easy to lose in the noise

Imagine a checkout service fails twenty-five times today. The team may see twenty-five separate log entries, but only a few underlying problems. It takes time to work out which failures are repeats, what changed, who is investigating, and whether a previous fix worked. That is the gap this application addresses.

## 3. One flow from error to learning

A Java service sends a sanitized error event to Error Intelligence. The backend checks the project key, removes sensitive data, and groups similar failures into an incident. Engineers can review evidence, comment, assign work, and record a verified resolution. Analytics then shows whether errors are improving.

## 4. Create and verify your account

A user creates an account and names a workspace. The app sends a one-time verification link, and sign-in is blocked until the email is confirmed. If the user forgets the password, the recovery page sends a short-lived reset link. Resetting the password signs out existing sessions.

## 5. Create a project for each service

After sign-in, create a project for the application you want to monitor. In this example it is the Checkout API. Each project owns its ingestion keys, error events, incidents, and analytics. An organization gives the team a shared workspace, while roles control who can change settings and who can only view.

## 6. Create a project ingestion key

Open the project and create an ingestion key. This is the credential that lets your Java service report errors to this project. The full value is displayed once; store it on the server, not in browser code or a Git repository. You can revoke a key and create another one if needed.

## 7. Your Java app sends the event

The dashboard does not automatically read errors from VS Code or your logs. Add a small server-side sender to your Java application. When an exception occurs, send an event ID, time, exception type, a safe message, and a sanitized stack trace to the ingestion endpoint. The backend validates the key and accepts the event.

## 8. Repeated events become incidents

In this demo, twenty-five invented checkout failures are grouped into three incidents: database timeouts, payment state errors, and inventory service failures. Instead of reading every event separately, an engineer sees the recurring problems and how often each happened. New events with the same fingerprint add to the existing group.

## 9. Open the incident evidence

Open an incident to see the recent event evidence, first and last seen times, occurrence count, status, and assignee. The team can add comments as they investigate. The purpose is to keep the evidence and discussion next to the issue, so the next engineer does not have to reconstruct the story from scratch.

## 10. Ask for diagnostic suggestions

The diagnostics and related-incidents pages can use configured AI services to suggest possible causes or find similar verified fixes. These suggestions are evidence to review, not automatic decisions. In the free local demo, paid AI workers are disabled, so this video does not pretend that an AI result was generated. The core investigation flow still works.

## 11. Resolve with a reason, not just a click

When the team confirms a fix, an authorized member writes a resolution summary and root cause, then verifies the incident as resolved. In the sample, the inventory dependency was unavailable during checkout. Keeping that explanation makes future incidents easier to compare and gives the team a record of what actually worked.

## 12. Analytics turns events into a view

The analytics page shows how many events arrived, how many incidents they formed, what remains open, and the resolution rate. It also shows daily volume and frequent incidents for the selected project. This helps a team decide what needs attention and check whether the number of failures is going down after a fix.

## 13. Keep access and changes visible

The account and team pages manage who belongs to a workspace. Roles restrict administrative actions, project data stays within its organization, and an audit log records important changes. Behind the scenes, the app uses short-lived access tokens, rotating refresh sessions, CSRF protection, and hashed project keys. The user sees a simple workflow, while these controls protect it.

## 14. A focused Java full-stack design

The React frontend sends same-origin requests to a Java twenty-one, Spring Boot backend. The backend handles identity, project permissions, error intake, grouping, investigation, and analytics. PostgreSQL stores the record of work. Redis supports distributed rate limits outside the native local profile. Optional AI workers can be enabled with a provider and budget; they are not required for the basic product.

## 15. What is ready, and what still needs setup

This local demo proves the connected application workflow with invented data. For everyday use, a Java service still needs an error sender. Hosted email delivery needs SMTP settings. Paid AI diagnostics need a configured provider, and public production staging remains an open deployment step. These are setup and release decisions, not features silently shown as complete in this video.

## 16. Start with one real service

To try the product, start the local database, Java backend, and frontend. Verify a new account, create a project and ingestion key, then send one synthetic error from your Java service or the guide's test command. Open the incident, investigate it, record a verified fix, and review analytics. The user guide beside this video has each command and screen.
