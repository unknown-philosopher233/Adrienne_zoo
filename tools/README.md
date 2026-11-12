# ZooApp / Adrienne Zoo Historical Data Pipeline (Phase P2)

Reproducible command-line tooling for auditing wildlife tracking sources, verifying redistribution rights, normalizing observations, and building validated v2 `.wildpack` archives.

Requires Python 3.11+ (standard library only, zero external packages).

## CLI Commands

Run from the repository root (`C:\Users\Dev\Documents\Adrienne_zoo`):

```bash
# 1. Inspect available studies, licenses, and tracks in a source dataset
python tools/wildpack_cli.py inspect-source data/tracks.json

# 2. Verify data redistribution rights & attribution completeness
python tools/wildpack_cli.py verify-rights data/tracks.json

# 3. Normalize and summarize a single animal track
python tools/wildpack_cli.py normalize-track data/tracks.json --study blue_whale --track 2008CA-Bmu-00830

# 4. Build & validate Starter Pack 1 (v2 replay-enabled .wildpack + build report)
python tools/wildpack_cli.py build-pack tools/configs/starter_pack_1.json

# 5. Validate any v1 or v2 .wildpack archive
python tools/wildpack_cli.py validate-pack packs/starter-pack-1.wildpack

# 6. Inspect metadata and animal timelines inside a .wildpack archive
python tools/wildpack_cli.py inspect-pack packs/starter-pack-1.wildpack
```

## Running Pipeline Tests

```bash
python -m unittest discover -s tools/tests -p "test_*.py" -v
```
