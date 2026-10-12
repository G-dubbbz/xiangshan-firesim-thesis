# Bare-metal "Hello XiangShan" for XiangShan in Chipyard.
#
# 1. Installs a trap handler (reports mcause/mepc/mtval via the [XSPROBE] log, see trap).
# 2. Prints a string on Chipyard UART0 (uncached MMIO -> XSTop peripheral port).
# 3. Waits until the UART has sent everything.
# 4. Reports PASS by writing 1 to tohost. tohost is in the uncached scratchpad at
#    0x0800_0000 (tests/link.ld), so the store leaves XiangShan immediately and the
#    host sees it; the simulation then exits with code 0.
# Run with tests/run-xs.sh (it zeroes tohost/fromhost with +init_write).
  .section .text.init
  .globl _start
_start:
  la    t0, trap
  csrw  mtvec, t0
  li    t1, 1
  slli  t1, t1, 42
  csrc  mstatus, t1         # clear MDT so a trap is reported, not a critical error

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
5:j     5b

  .align 2
trap:                       # write mcause, mepc, mtval to UART0 `ie` (harmless; shows in probe)
  li    t0, 0x10020000
  csrr  t1, mcause
  sw    t1, 0x10(t0)
  csrr  t1, mepc
  sw    t1, 0x10(t0)
  csrr  t1, mtval
  sw    t1, 0x10(t0)
6:j     6b

  .section .rodata
msg: .string "Hello XiangShan\n"
