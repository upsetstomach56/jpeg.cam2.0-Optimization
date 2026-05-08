#!/bin/bash
# Purges GitHub Actions artifacts and completed run logs for this repo.
# Requires: gh CLI (brew install gh) + jq (brew install jq), both authenticated.

set -e

REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null)
if [ -z "$REPO" ]; then
  echo "ERROR: Could not detect repo. Run this from inside the jpegcam directory."
  exit 1
fi

echo "Repo: $REPO"
echo ""

# ── 1. Delete all artifacts ──────────────────────────────────────────────────
echo "Fetching artifacts..."
ARTIFACT_IDS=$(gh api "repos/$REPO/actions/artifacts?per_page=100" --paginate \
  | jq '.artifacts[].id')

COUNT=$(echo "$ARTIFACT_IDS" | grep -c . || true)
if [ "$COUNT" -eq 0 ]; then
  echo "No artifacts found."
else
  echo "Deleting $COUNT artifact(s)..."
  echo "$ARTIFACT_IDS" | xargs -I{} gh api --method DELETE "repos/$REPO/actions/artifacts/{}"
  echo "Done."
fi

echo ""

# ── 2. Delete completed workflow runs ────────────────────────────────────────
echo "Fetching completed runs..."
RUN_IDS=$(gh run list --limit 200 --json databaseId,status \
  | jq '[.[] | select(.status != "in_progress" and .status != "queued")] | .[].databaseId')

RCOUNT=$(echo "$RUN_IDS" | grep -c . || true)
if [ "$RCOUNT" -eq 0 ]; then
  echo "No completed runs found."
else
  echo "Deleting $RCOUNT run(s)..."
  echo "$RUN_IDS" | xargs -I{} gh run delete {}
  echo "Done."
fi

echo ""
echo "All clear. Check storage at: https://github.com/$REPO/settings/billing"
