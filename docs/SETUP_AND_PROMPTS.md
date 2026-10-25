# Import into Claude Code — Quick Start

## Step 1: Unzip
Extract the ZIP so you have a folder `pho.psd-claude-starter/` containing `CLAUDE.md`, `docs/` and `design-reference.png`. You may rename this folder to `pho.psd`.

## Step 2: Place into your actual project
If your Android project is NEW, use this folder as the project root; Claude Code can create the Android app inside it. If your app ALREADY exists, copy `CLAUDE.md`, the entire `docs/` folder and `design-reference.png` into the **root of the existing repository**, next to `settings.gradle.kts`/`build.gradle.kts`. Don't replace existing source files. If your repo already has a `CLAUDE.md`, merge these instructions instead of overwriting it.

## Step 3: Run Claude Code from that folder
From a terminal on the computer with Claude Code installed:

```bash
cd /path/to/pho.psd
claude
```

`CLAUDE.md` in the project root is automatically loaded by Claude Code at session startup. The other Markdown files are read from disk on request; don't paste their contents into chat. If you added `CLAUDE.md` during an existing session, start a new session so it is loaded.

## Step 4: Paste this short prompt
```text
Read the project instructions and docs, and inspect design-reference.png. Inspect the repo first. Start Milestones 0–1, then implement working image import and basic layer compositing from Milestone 2 as time permits. Keep the app compiling and update PROJECT_STATUS.md, ARCHITECTURE.md and FEATURE_MATRIX.md with verified progress. Do not produce UI-only fake tools.
```

## Step 5: Continue in controlled chunks
After a work cycle, request:
```text
Review ROADMAP.md and PROJECT_STATUS.md. Fix failing builds/tests first. Continue the next incomplete milestone only, make its features functional, and update progress docs with verified results.
```

## Handy checks
- Confirm working directory contains `CLAUDE.md` and `docs/`.
- Ask Claude: `Summarize your project instructions and identify the first milestone; do not write code yet.`
- Watch build output. If Android SDK/NDK is missing, set it up before expecting a working APK.
- Git commit after a verified clean checkpoint so you can restore a previous state.
- If Claude loses track of the context, have it re-read `PROJECT_STATUS.md` and the relevant docs rather than pasting the original long specification again.

## If you are using a browser-based Claude interface instead of Claude Code
Uploading a ZIP or Markdown files to a regular Claude chat does not automatically make a runnable Android project. Use a Claude Code environment connected to your project files (local terminal, supported IDE, or repository workspace), and place the files into its workspace. Then ask Claude Code to read them.
