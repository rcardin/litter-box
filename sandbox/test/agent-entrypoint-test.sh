#!/usr/bin/env bash
# AC2: agent-entrypoint.sh must return the agent's work as a patch that is
# CUMULATIVE vs origin/main — the shape the host's stage_patch applies onto a pristine base.
# The riskiest part is that a FIX builds on the PRIOR cumulative patch, so its diff has to carry
# both the prior work AND the new edit. This test drives the entrypoint on the host (paths made
# overridable for exactly this) with a fake `claude`, so it needs no container, no key, no daemon.
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
ENTRYPOINT="$SCRIPT_DIR/agent-entrypoint.sh"

pass=0; fail=0
check() { if [[ "$2" == "$3" ]]; then echo "  ok   $1"; pass=$((pass+1)); else echo "  FAIL $1 (want=$2 got=$3)"; fail=$((fail+1)); fi; }

SB="$(mktemp -d)"; trap 'rm -rf "$SB"' EXIT
BIN="$SB/bin"; mkdir -p "$BIN"; export PATH="$BIN:$PATH"

# origin/main baseline: one tracked file.
ORIGIN="$SB/origin"; mkdir -p "$ORIGIN"
printf 'object Base\n' > "$ORIGIN/Base.scala"

# A fake `claude` that appends a line to Impl.scala inside the workspace it is run in. Stands in
# for the agent editing the tree; the entrypoint's job is to turn those edits into the patch.
cat > "$BIN/claude" <<'CL'
#!/usr/bin/env bash
printf 'object Impl // edited\n' >> Impl.scala
CL
chmod +x "$BIN/claude"

run_entrypoint() { # run_entrypoint WORKDIR PRIOR_PATCH -> writes $OUT/agent.patch
  local work="$1" prior="$2" inp="$3" out="$4"
  rm -rf "$work" "$inp" "$out"; mkdir -p "$work" "$inp" "$out"
  cp "$ORIGIN"/* "$work"/            # workspace seeded with the origin/main tree
  printf 'do the thing' > "$inp/prompt.txt"
  if [[ -n "$prior" ]]; then cp "$prior" "$inp/prior.patch"; else : > "$inp/prior.patch"; fi
  ( AGENT_WORKSPACE="$work" AGENT_INPUT="$inp" AGENT_OUTPUT="$out" bash "$ENTRYPOINT" ) >/dev/null 2>&1
}

# --- Case 1: IMPL (no prior patch). Patch must add only Impl.scala, and apply cleanly onto a
# pristine origin/main checkout. -------------------------------------------------------------
rc1=0; run_entrypoint "$SB/w1" "" "$SB/i1" "$SB/o1" || rc1=$?
check "IMPL entrypoint exits 0" 0 "$rc1"
IMPL_PATCH="$SB/o1/agent.patch"
check "IMPL patch adds Impl.scala" "1" "$(grep -c '^+++ b/Impl.scala' "$IMPL_PATCH" || true)"
check "IMPL patch does not touch Base.scala" "0" "$(grep -c 'Base.scala' "$IMPL_PATCH" || true)"
# Apply onto a pristine origin/main, exactly as the host does.
V1="$SB/verify1"; rm -rf "$V1"; mkdir -p "$V1"; cp "$ORIGIN"/* "$V1"/
( cd "$V1"; git init -q; git add -A; git -c user.email=a@b -c user.name=a commit -qm base
  git apply --index "$IMPL_PATCH" ) && a1=0 || a1=1
check "IMPL patch applies onto pristine origin/main" 0 "$a1"
check "IMPL applied tree has the agent's edit" "object Impl // edited" "$(cat "$V1/Impl.scala" 2>/dev/null)"

# --- Case 2: FIX (prior patch present). The prior patch adds Prior.scala; the fixer edits
# Impl.scala on top. The returned patch must be CUMULATIVE — carry BOTH files — so applying it
# onto a pristine origin/main reproduces prior work + the new edit. ---------------------------
PRIOR="$SB/prior.patch"
{
  printf 'diff --git a/Prior.scala b/Prior.scala\n'
  printf 'new file mode 100644\n--- /dev/null\n+++ b/Prior.scala\n'
  printf '@@ -0,0 +1,1 @@\n+object Prior\n'
} > "$PRIOR"
rc2=0; run_entrypoint "$SB/w2" "$PRIOR" "$SB/i2" "$SB/o2" || rc2=$?
check "FIX entrypoint exits 0" 0 "$rc2"
FIX_PATCH="$SB/o2/agent.patch"
check "FIX patch carries prior work (Prior.scala)" "1" "$(grep -c '^+++ b/Prior.scala' "$FIX_PATCH" || true)"
check "FIX patch carries the new edit (Impl.scala)" "1" "$(grep -c '^+++ b/Impl.scala' "$FIX_PATCH" || true)"
V2="$SB/verify2"; rm -rf "$V2"; mkdir -p "$V2"; cp "$ORIGIN"/* "$V2"/
( cd "$V2"; git init -q; git add -A; git -c user.email=a@b -c user.name=a commit -qm base
  git apply --index "$FIX_PATCH" ) && a2=0 || a2=1
check "FIX cumulative patch applies onto pristine origin/main" 0 "$a2"
check "FIX applied tree has prior work"   "object Prior"        "$(cat "$V2/Prior.scala" 2>/dev/null)"
check "FIX applied tree has the new edit" "object Impl // edited" "$(cat "$V2/Impl.scala" 2>/dev/null)"

# --- Case 3: a prior patch that does NOT apply is an infra fault (exit 3). --------------------
BADPRIOR="$SB/bad.patch"
{
  printf 'diff --git a/Base.scala b/Base.scala\n'
  printf 'new file mode 100644\n--- /dev/null\n+++ b/Base.scala\n'   # Base.scala already exists -> conflict
  printf '@@ -0,0 +1,1 @@\n+object Dup\n'
} > "$BADPRIOR"
rc3=0; run_entrypoint "$SB/w3" "$BADPRIOR" "$SB/i3" "$SB/o3" || rc3=$?
check "prior patch that will not apply -> exit 3 (infra fault)" 3 "$rc3"

echo
echo "==== $pass passed, $fail failed ===="
[[ "$fail" -eq 0 ]]
