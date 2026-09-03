# Environment patches

## `chipyard-local-container-compat.patch`

Applies to the Chipyard parent commit recorded in:

```text
../../environment/chipyard-parent-commit.txt
```

This patch is required for the local Docker `linux/amd64` setup. It
changes Chipyard setup/dependency scripts only:

- Conda libmamba solver setting compatibility.
- Conda sysroot adjustment from GLIBC 2.34 to 2.35.
- Partial Git clone filtering for selected submodules.

It does not modify XiangShan, Rocket Chip, TileLink, Chipyard generators,
Verilator simulation logic, or FireSim target hardware.

Validate application against a clean checkout:

```bash
git apply --check chipyard-local-container-compat.patch
```

Validate it against an already patched checkout:

```bash
git apply --reverse --check chipyard-local-container-compat.patch
```
