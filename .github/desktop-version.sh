#!/usr/bin/env bash
# Version des installeurs : X.Y.Z tiré de l’étiquette desktop-vX.Y.Z, sinon 1.0.0.
# macOS refuse une version dont le premier nombre est 0.
set -euo pipefail
version=1.0.0
if [[ "${GITHUB_REF:-}" == refs/tags/desktop-v* ]]; then version="${GITHUB_REF#refs/tags/desktop-v}"; fi
if [[ ! "$version" =~ ^[1-9][0-9]*\.[0-9]+\.[0-9]+$ ]]; then
  echo "Version invalide « $version » : attendu X.Y.Z avec X ≥ 1" >&2
  exit 1
fi
echo "$version"
