#!/bin/bash
set -e # Exit immediately if a command exits with a non-zero status.

# 1. Fetch all tags from remote to ensure we have the latest.
echo "Fetching tags from remote..."
git fetch --tags --quiet

# 2. Find the latest tag matching the vX.Y.Z pattern.
# We sort by version to get the highest one.
# Handles tags like v0.1.0, v1.2.3, v1.10.5, etc.
LATEST_TAG=$(git tag -l "v*.*.*" --sort=-v:refname | head -n 1)

# Default version if no tags are found (you'll likely have one already)
DEFAULT_INITIAL_TAG="v0.0.0"

if [ -z "$LATEST_TAG" ]; then
  echo "No existing vX.Y.Z tag found. Using default $DEFAULT_INITIAL_TAG as base for the first patch."
  LATEST_TAG=$DEFAULT_INITIAL_TAG
fi

echo "Latest tag found: $LATEST_TAG"

# 3. Parse the version string (remove 'v' prefix and split)
VERSION_PARTS=$(echo "$LATEST_TAG" | sed 's/^v//' | tr "." "\n")
MAJOR=$(echo "$VERSION_PARTS" | sed -n '1p')
MINOR=$(echo "$VERSION_PARTS" | sed -n '2p')
PATCH=$(echo "$VERSION_PARTS" | sed -n '3p')

if [ -z "$MAJOR" ] || [ -z "$MINOR" ] || [ -z "$PATCH" ]; then
  echo "Error: Could not parse major, minor, or patch version from tag '$LATEST_TAG'."
  echo "Ensure your tags are in the format vX.Y.Z (e.g., v0.4.5)."
  exit 1
fi

# 4. Increment the patch number
NEW_PATCH=$((PATCH + 1))

# 5. Construct the new tag name
NEW_TAG="v${MAJOR}.${MINOR}.${NEW_PATCH}"

echo "Current version: $LATEST_TAG"
echo "New version will be: $NEW_TAG"

# Optional: Confirm before tagging and pushing
read -p "Create and push tag $NEW_TAG? (y/N): " confirm
if [[ "$confirm" != "y" && "$confirm" != "Y" ]]; then
  echo "Aborted."
  exit 0
fi

# 6. Create the new tag locally
echo "Creating tag $NEW_TAG..."
git tag "$NEW_TAG"

# 7. Push the new tag to the remote (origin by default)
echo "Pushing tag $NEW_TAG to remote..."
git push origin "$NEW_TAG"

echo "Successfully tagged and pushed $NEW_TAG."
echo "Your sbt-dynver should now pick this up on the next build from this commit/tag."
