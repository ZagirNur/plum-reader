#!/usr/bin/env bash
#
# Plum Reader API happy-path demo.
#
# Spins through register → upload → wait split+markup → read pages →
# vocabulary, against a locally-running server (default localhost:8080).
#
# Requires: curl, jq, an EPUB file at the given path.
#
# Usage:
#   ./happy-path.sh                                   # uses /tmp/epubs/pride.epub
#   ./happy-path.sh /path/to/book.epub
#   BASE=http://api.example.com ./happy-path.sh ...

set -euo pipefail
BASE="${BASE:-http://localhost:8080}"
EPUB="${1:-/tmp/epubs/pride.epub}"
EMAIL="${EMAIL:-demo$(date +%s)@plum.test}"
PWD="${PWD_:-supersecret-pass-12}"

[ -f "$EPUB" ] || { echo "EPUB not found at $EPUB"; exit 1; }

echo "== 1. Register =="
TOK=$(curl -fsS -X POST "$BASE/api/v1/auth/register" \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PWD\",\"name\":\"Demo\"}" \
  | jq -r .token)
echo "got token: ${#TOK} chars"

echo
echo "== 2. /me =="
curl -fsS -H "Authorization: Bearer $TOK" "$BASE/api/v1/me" | jq

echo
echo "== 3. Upload '$EPUB' =="
UP=$(curl -fsS -X POST "$BASE/api/v1/books/upload" \
  -H "Authorization: Bearer $TOK" \
  -F "file=@$EPUB")
BOOK_ID=$(echo "$UP" | jq -r .book.id)
JOB_ID=$(echo "$UP" | jq -r .jobId)
DEDUP=$(echo "$UP" | jq -r .deduplicated)
echo "$UP" | jq
echo "→ bookId=$BOOK_ID jobId=$JOB_ID deduplicated=$DEDUP"

echo
echo "== 4. Wait for split + markup =="
for i in $(seq 1 60); do
  B=$(curl -fsS -H "Authorization: Bearer $TOK" "$BASE/api/v1/books/$BOOK_ID")
  S=$(echo "$B" | jq -r .status)
  M=$(echo "$B" | jq -r .markupStatus)
  echo "t+${i}s  status=$S  markupStatus=$M"
  [ "$S" = "ready" ] && [ "$M" = "ready" ] && break
  [ "$S" = "failed" ] && { echo "FAILED: $(echo "$B" | jq -r .error)"; exit 2; }
  sleep 1
done

echo
echo "== 5. Library =="
curl -fsS -H "Authorization: Bearer $TOK" "$BASE/api/v1/books" | jq

echo
echo "== 6. Page list =="
curl -fsS -H "Authorization: Bearer $TOK" "$BASE/api/v1/books/$BOOK_ID/pages" | jq

echo
echo "== 7. First page (truncated) =="
curl -fsS -H "Authorization: Bearer $TOK" "$BASE/api/v1/books/$BOOK_ID/pages/0" \
  | jq '{bookId, idx, total, textLen, prevIdx, nextIdx, xhtml: (.xhtml[:300] + "…")}'

echo
echo "== 8. Save progress at page 5 =="
curl -fsS -X PATCH "$BASE/api/v1/books/$BOOK_ID/progress" \
  -H "Authorization: Bearer $TOK" \
  -H "Content-Type: application/json" \
  -d '{"lastPageIdx":5}' | jq

echo
echo "== 9. Top 10 vocabulary =="
curl -fsS -H "Authorization: Bearer $TOK" \
  "$BASE/api/v1/books/$BOOK_ID/vocabulary?limit=10" | jq

echo
echo "== 10. Specific words =="
for w in elizabeth darcy bennet wickham; do
  echo -n "  $w → "
  curl -fsS -H "Authorization: Bearer $TOK" \
    "$BASE/api/v1/books/$BOOK_ID/words/$w" | jq -c
done

echo
echo "== Done. =="
