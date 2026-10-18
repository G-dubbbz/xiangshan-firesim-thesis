OUTPUT_ARCH(riscv)
ENTRY(_start)
tohost   = 0x08000000;
fromhost = 0x08000040;
SECTIONS
{
  . = 0x80000000;
  .text   : { *(.text.init) *(.text .text.*) }
  . = ALIGN(0x1000);
  .rodata : { *(.rodata .rodata.* .srodata .srodata.*) }
  .data   : { *(.data .data.*) }
  .sdata  : { __global_pointer$ = . + 0x800; *(.sdata .sdata.*) }
  . = ALIGN(8);
  __bss_start = .;
  .sbss   : { *(.sbss .sbss.*) }
  .bss    : { *(.bss .bss.* COMMON) }
  . = ALIGN(8);
  _end = .;
  PROVIDE(end = _end);
  . = ALIGN(16);
  . += 0x10000;
  _stack_top = .;
  _heap_start = .;
}
