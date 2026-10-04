#!/usr/bin/env bash
# Cherche des clés d'API ou d'autres secrets dans ce qui est (ou a été) versionné.
#   outils/chercher-secrets.sh              les fichiers suivis par git
#   outils/chercher-secrets.sh --historique  tous les commits de toutes les branches, messages compris
# Sort avec le code 1 dès qu'un motif est trouvé. Utilisé par l'intégration continue.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

MOTIFS='sk_[0-9a-f]{30,}|sk-proj-[A-Za-z0-9_-]{20,}|sk-ant-[A-Za-z0-9_-]{20,}|sk-[A-Za-z0-9]{40,}|AKIA[0-9A-Z]{16}|ghp_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|xox[baprs]-[A-Za-z0-9-]{10,}|-----BEGIN [A-Z ]*PRIVATE KEY-----'

trouve=0
if [ "${1:-}" = "--historique" ]; then
	if git log --all -p --no-color --format='%H %s%n%b' | grep -nE "$MOTIFS" | cut -c1-80; then
		trouve=1
	fi
else
	if git grep -nIE "$MOTIFS" -- . ':!outils/chercher-secrets.sh' | cut -c1-80; then
		trouve=1
	fi
	# Un fichier de clé ne doit jamais être suivi, quel que soit son contenu.
	if git ls-files | grep -E '(^|/)(hameau-cles/|[^/]*-cle\.txt$|\.env($|\.))'; then
		trouve=1
	fi
fi

if [ "$trouve" = 1 ]; then
	echo "Secret possible : voir les lignes ci-dessus." >&2
	exit 1
fi
echo "Aucun secret trouvé."
