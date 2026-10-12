# Bare-metal "Hello XiangShan!" for XiangShan in Chipyard.
# Writes to Chipyard's UART0 (MMIO, uncached -> XSTop peripheral port), then spins.
  .section .text.init
  .globl _start
_start:
  li    t0, 0x10020000      # Chipyard UART0 base
  li    t1, 1
  sw    t1, 8(t0)           # txctrl.txen = 1
  la    t2, msg
1:lbu   t3, 0(t2)
  beqz  t3, 2f
3:lw    t4, 0(t0)           # txdata; bit 31 = FIFO full (reads negative)
  bltz  t4, 3b
  sw    t3, 0(t0)           # transmit byte
  addi  t2, t2, 1
  j     1b
2:j     2b
  .section .rodata
msg: .string "Hello XiangShan!\n"
  .section .tohost, "aw", @progbits
  .align 6
  .globl tohost
tohost: .dword 0
  .align 6
  .globl fromhost
fromhost: .dword 0
