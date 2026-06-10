# Task: Sanitize Hermes-Agent-Mobile-Client for Public GitHub

You are working in `/path/to/Hermes-Agent-Mobile-Client` — an Android WebView client for Hermes Agent.

## Goal
Prepare this project for open-source publication on GitHub. Perform a full audit and sanitize all sensitive data.

## Step 1: Audit — Find all sensitive data

Search every file under android/, packages/, scripts/, *.md, *.json, *.kt, *.java, *.xml, *.gradle, *.kts for:
- Real private or public IP addresses tied to an individual's infrastructure
- Real domain names tied to the user's infrastructure
- Aliyun/private container registry URLs
- Hardcoded passwords, secrets, API keys, tokens (any hex string 24+ chars, or words like "password", "secret", "token", "key" followed by a value)
- Personal usernames or account identifiers hardcoded in source
- Internal hostnames that are not publicly known

List every finding with file path + line number.

## Step 2: Sanitize — Replace with placeholders

For each finding:
- Real URLs/domains used as default server addresses → replace with `https://your-hermes-server.example.com` (or similar)
- Any hardcoded credentials → move to a separate config file that is .gitignored, or replace with empty string / prompt user to configure
- Internal-only references → replace with generic descriptive text

## Step 3: .gitignore

Check the existing .gitignore (if any). Ensure it excludes:
- local.properties (contains Android SDK path)
- *.keystore, *.jks (signing keys)
- .env, *.env.local
- google-services.json (if present)
- /apk/ directory (build outputs — already excluded?)
- Any file with the word "secret" or "key" in the name

Add any missing entries.

## Step 4: Create/update README.md at project root

Update README.md to:
- Describe what this app does (Android native client for Hermes Agent, wrapping the WebUI in a WebView with auto-login and mobile UX fixes)
- Explain how to configure the server URL before building
- Show build instructions (JAVA_HOME, ANDROID_HOME, gradlew assembleDebug)
- Remove any real domain/IP from README

## Step 5: Verify

After all changes:
- Run the sensitive-pattern verification grep from the task handoff to confirm no sensitive data remains
- Report any remaining hits

## Constraints
- DO NOT modify any build logic or app functionality
- DO NOT commit build outputs (apk/, .gradle/, build/)
- Only sanitize values — keep all code structure intact
- Commit all changes: `git add -A && git commit -m "chore: sanitize for public release"`
- Report DONE with a summary of all changes made
