OUTPUT_ARCH("riscv")
ENTRY(_start)

/* tohost/fromhost: host-communication words, placed in the uncached scratchpad
 * added by chipyard.XiangShanConfig (0x0800_0000), NOT in DRAM:
 *   - XiangShan's caches are not coherent with the host, so a tohost in DRAM
 *     would never be seen;
 *   - they are absolute symbols, not a loadable section, so LOADMEM (which only
 *     loads DRAM) never touches them; the host finds them by symbol name.
 * The scratchpad starts with random contents in simulation, so every run must
 * zero them first: tests/run-xs.sh passes +init_write for both. */
tohost   = 0x08000000;
fromhost = 0x08000040;

SECTIONS
{
  . = 0x80000000;
  .text.init : { *(.text.init) }
  .text      : { *(.text*) }
  .rodata    : { *(.rodata*) }
  .data      : { *(.data*) }
  .bss       : { *(.bss*) }
}
