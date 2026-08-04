# Setting up `rcardin/homebrew-tap`

The `formula` job in `.github/workflows/release.yml` renders a Homebrew formula on every tagged
release and pushes it to `rcardin/homebrew-tap`. That repository, and the credential the job
authenticates with, are both manual, one time steps a workflow cannot do for itself: nothing in
this repository's own automation is allowed to create a repository or set a secret on your behalf.
Until both are done, `brew tap rcardin/tap` 404s; run `gh repo view rcardin/homebrew-tap` (or check
the releases page and the ghcr package page, which do not depend on this repository existing at
all) to see what is actually live today rather than trusting a snapshot written here.

## 1. Create the tap repository

Create `rcardin/homebrew-tap` on GitHub, public, with at least one commit on a branch named
`main`. `main` only matters for that first commit: the `formula` job does not assume it stays the
default branch afterward. It reads the tap's default branch off the remote at push time
(`git symbolic-ref --quiet --short refs/remotes/origin/HEAD`, falling back to `git remote show
origin` if that comes back empty) and pushes there, so renaming the tap's default branch later,
for any reason, is safe; nothing here has to be told about the rename. `main` is used as a literal
fallback only for a tap with no commits yet at all, the one case with no default branch for the
job to read, which is why the step below still has to create one. A minimal way to do this from a
shell:

```bash
mkdir homebrew-tap && cd homebrew-tap
git init -b main
git commit --allow-empty -m "init"
gh repo create rcardin/homebrew-tap --public --source=. --push
```

Homebrew's own naming convention for a tap repository is the `homebrew-` prefix; `brew tap
rcardin/tap` (the short form README.md's Install section documents) is what makes brew look for
`rcardin/homebrew-tap` under that convention.

## 2. Add the `HOMEBREW_TAP_TOKEN` secret

Add a repository secret named `HOMEBREW_TAP_TOKEN` to `rcardin/litter-box` (repository Settings,
Secrets and variables, Actions), holding a token with write access to `rcardin/homebrew-tap`:
either a fine grained personal access token scoped to that one repository with Contents set to
read and write, or a classic token carrying the `repo` scope. The `formula` job fails loudly with
`HOMEBREW_TAP_TOKEN is not set` rather than silently skipping when this is missing, so a missing
secret reads as an operator problem to fix rather than a release that quietly shipped a binary and
an image with no updated formula.

## What still works before both steps are done

Nothing here affects the rest of the release pipeline: `formula` is the last of five jobs, and its
own failure does not roll back `build`, `smoke`, `image` or `release`. A tag still publishes a
binary attached to a GitHub release and a sandbox base image on ghcr with neither step done; only
the Homebrew path in README.md's Install section stays unavailable until both are.

## Space tags out

`release.yml`'s whole workflow runs in one `concurrency: group: release`, on purpose, so two tags
never race each other inside the `formula` job's push to this tap. The cost of that: GitHub only
keeps one run queued behind the one in progress, not an unbounded backlog. Push a third tag while
a second is still queued and the second is cancelled outright, not delayed, with no release, no
image and no formula update for it. Push tags one at a time and let each run finish (or fail) before
cutting the next.
