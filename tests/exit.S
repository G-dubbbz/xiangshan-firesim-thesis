# xs-exit: minimal end-to-end check of the PASS path (no UART printing, a few
# thousand cycles). Writes 1 to tohost in the uncached scratchpad (tests/link.ld).
# Expected: the simulation exits by itself with code 0.
# If anything traps, the handler writes mcause, mepc, mtval to UART0's `ie`
# register (0x10020010); each value shows up in the [XSPROBE] log as a periph W.
# Run with tests/run-xs.sh (it zeroes tohost/fromhost with +init_write).
  .section .text.init
  .globl _start
_start:
  la    t0, trap
  csrw  mtvec, t0
  li    t1, 1
  slli  t1, t1, 42
  csrc  mstatus, t1         # clear MDT (mstatus bit 42) so traps are reported

  la    t0, tohost
  li    t1, 1               # tohost = (exit_code << 1) | 1, exit_code 0 = PASS
  sd    t1, 0(t0)
1:j     1b

  .align 2
trap:
  li    t0, 0x10020000      # UART0
  csrr  t1, mcause
  sw    t1, 0x10(t0)        # -> probe: periph W data = mcause
  csrr  t1, mepc
  sw    t1, 0x10(t0)        # -> probe: periph W data = mepc (low 32 bits)
  csrr  t1, mtval
  sw    t1, 0x10(t0)        # -> probe: periph W data = mtval (low 32 bits)
2:j     2b
