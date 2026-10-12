# Bare-metal "Hello XiangShan!" for XiangShan in Chipyard.
#
# 1. Prints a string on Chipyard UART0 (uncached MMIO -> XSTop peripheral port).
# 2. Waits until the UART has sent everything.
# 3. Reports PASS to the host by writing 1 to tohost, so the simulation exits cleanly.
#
# tohost is in DRAM, which XiangShan caches in its own write-back L1/L2/L3 inside
# XSTop. Those caches are not coherent with the Chipyard host (fesvr reads tohost
# from DRAM over serial-TL), so the PASS store is pushed out explicitly:
#   fence      drain XiangShan's store buffer (it otherwise holds an idle store
#              for up to 2^20 cycles) into the L1 D-cache
#   cbo.flush  write the dirty line back through XiangShan's caches to DRAM (Zicbom)
  .section .text.init
  .globl _start
_start:
  li    t0, 0x10020000      # UART0 base
  li    t1, 0x10001         # txctrl: txen=1, txcnt=1 -> ip.txwm set when TX FIFO is empty
  sw    t1, 8(t0)
  la    t2, msg
1:lbu   t3, 0(t2)
  beqz  t3, 2f
3:lw    t4, 0(t0)           # txdata: bit 31 = FIFO full (reads negative)
  bltz  t4, 3b
  sw    t3, 0(t0)           # transmit byte
  addi  t2, t2, 1
  j     1b
2:lw    t4, 0x14(t0)        # ip: bit 0 = txwm (TX FIFO empty)
  andi  t4, t4, 1
  beqz  t4, 2b
  li    t4, 200000          # let the last character finish shifting out (~90k cycles/char)
4:addi  t4, t4, -1
  bnez  t4, 4b
  la    t0, tohost
  li    t1, 1               # tohost = (exit_code << 1) | 1, exit_code 0 = PASS
  sd    t1, 0(t0)
  fence
  .insn i 0x0F, 2, x0, t0, 2   # cbo.flush 0(t0); .insn so no assembler extension flag is needed
  fence
5:j     5b

  .section .rodata
msg: .string "Hello XiangShan\n"

  .section .tohost, "aw", @progbits
  .align 6
  .globl tohost
tohost: .dword 0
  .align 6
  .globl fromhost
fromhost: .dword 0
