#!/usr/bin/env bash
# =============================================================================
# publish-channel.sh
#
# Refresh the private JetBrains update channel (updatePlugins.xml) so that
# external users who added the channel URL to IDEA get an auto-update prompt.
#
# Intranet / offline users are unaffected: they keep installing the zip from
# GitHub Releases manually.
#
# What it does:
#   1. Switch to the gh-pages branch (creates it from main if missing).
#   2. Rewrite updatePlugins.xml with the new version + release asset URL.
#   3. Commit & push, which triggers GitHub Pages to serve the new XML.
#
# Usage:
#   ./scripts/publish-channel.sh <tag> [asset-name]
#
# Examples:
#   ./scripts/publish-channel.sh v3.0.17
#   ./scripts/publish-channel.sh v3.0.17 CoStrict-3.0.17.zip
#
# Defaults:
#   asset-name  ->  CoStrict-<version-without-v>.zip   (the universal/lite bundle)
#
# Note on the bundle choice:
#   The universal/lite bundle (no platform suffix) holds the Java plugin + JS
#   resources but NOT the Node.js binary. It is the right artifact for the
#   auto-update channel because the plugin downloads platform-specific Node.js
#   on first launch (scripts/setup-node-online.{sh,bat}). Platform bundles
#   (CoStrict-<ver>-<os>-<arch>.zip) with builtin Node.js are for first-time
#   offline install only and are not referenced here.
# Requirements:
#   - gh CLI authenticated, OR push access to the repo.
#   - Run from a clean working tree (the script switches branches).
# =============================================================================
set -euo pipefail

TAG="${1:?usage: $0 <tag> [asset-name]  e.g. v3.0.17}"
ASSET="${2:-}"
VERSION="${TAG#v}"
# Default to the universal bundle (the one IDEA auto-update consumes).
[[ -z "$ASSET" ]] && ASSET="CoStrict-${VERSION}.zip"

REPO_SLUG="${COSTRICT_REPO:-zgsm-sangfor/costrict-for-jetbrains}"
ASSET_URL="https://github.com/${REPO_SLUG}/releases/download/${TAG}/${ASSET}"
PLUGIN_ID="${COSTRICT_PLUGIN_ID:-CoStrict}"
SINCE_BUILD="${COSTRICT_SINCE_BUILD:-233}"
# Leave UNTIL_BUILD empty to match the plugin's own plugin.xml (patchPluginXml
# sets untilBuild=""). A fixed upper bound here would filter out newer IDEAs
# (e.g. 2026.x, build 262+) from ever seeing the update. Override via
# COSTRICT_UNTIL_BUILD if you ever need to cap compatibility.
UNTIL_BUILD="${COSTRICT_UNTIL_BUILD:-}"
BRANCH=gh-pages

# Bail out if there are uncommitted changes (we're about to switch branches).
if ! git diff --quiet || ! git diff --cached --quiet; then
  echo "ERROR: working tree has uncommitted changes; commit or stash first." >&2
  exit 1
fi

INITIAL_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
restore_branch() {
  echo ">> restoring branch: ${INITIAL_BRANCH}"
  git checkout -f "$INITIAL_BRANCH" >/dev/null 2>&1 || true
}
trap restore_branch EXIT

# Create gh-pages if it doesn't exist.
if ! git show-ref --verify --quiet "refs/heads/${BRANCH}"; then
  echo ">> creating orphan ${BRANCH} branch"
  git checkout --orphan "$BRANCH"
  git rm -rf . >/dev/null 2>&1 || true
  git commit --allow-empty -m "chore: init ${BRANCH}" >/dev/null
else
  echo ">> checking out existing ${BRANCH} branch"
  git checkout "$BRANCH" >/dev/null
fi

echo ">> writing updatePlugins.xml (id=${PLUGIN_ID}, version=${VERSION})"
mkdir -p downloads
# Build the idea-version tag; omit until-build when empty (no upper bound),
# matching the plugin's own plugin.xml which leaves untilBuild unset.
IDEA_VERSION_TAG="<idea-version since-build=\"${SINCE_BUILD}\""
if [[ -n "$UNTIL_BUILD" ]]; then
  IDEA_VERSION_TAG+=" until-build=\"${UNTIL_BUILD}\""
fi
IDEA_VERSION_TAG+="/>"
cat > updatePlugins.xml <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<plugins>
  <plugin
      id="${PLUGIN_ID}"
      version="${VERSION}"
      url="${ASSET_URL}">
    <name>CoStrict</name>
    ${IDEA_VERSION_TAG}
    <description>CoStrict AI Coding Assistant</description>
  </plugin>
</plugins>
EOF

git add updatePlugins.xml
git commit -m "chore: bump update channel to ${VERSION}" >/dev/null
echo ">> pushing ${BRANCH}"
git push -u origin "$BRANCH"

echo ""
echo "Done. Channel will be live at:"
echo "  https://${REPO_SLUG/\//.}.github.io/${REPO_SLUG#*/}/updatePlugins.xml"
echo ""
echo "If Pages is not yet enabled, configure it once:"
echo "  Repo Settings -> Pages -> Source: Deploy from a branch -> ${BRANCH} / root"
