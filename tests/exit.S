# xs-exit: minimal test of the "report PASS via tohost" sequence, with trap reporting.
#
# Runs in a few thousand cycles (no UART printing). If any instruction traps, the
# handler writes mcause, mepc and mtval (low 32 bits each) to UART0's `ie` register
# (0x10020010). That is harmless, and each write shows up in the [XSPROBE] log as
#   periph AW addr=10020010 ... / periph W ... data=<value><value>
# so the cause and faulting PC can be read from xs-exit.out.
#
# Without a handler, any trap in M-mode right after reset puts XiangShan into
# critical-error state (mstatus.MDT=1 at reset), which hides the cause. So we
# install mtvec and clear MDT (mstatus bit 42, Smdbltrp) first.
  .section .text.init
  .globl _start
_start:
  la    t0, trap
  csrw  mtvec, t0
  li    t1, 1
  slli  t1, t1, 42
  csrc  mstatus, t1         # clear MDT

  la    t0, tohost
  li    t1, 1               # tohost = (exit_code << 1) | 1, exit_code 0 = PASS
  sd    t1, 0(t0)
  fence                     # drain store buffer
  .insn i 0x0F, 2, x0, t0, 2   # cbo.flush 0(t0)
  fence
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

  .section .tohost, "aw", @progbits
  .align 6
  .globl tohost
tohost: .dword 0
  .align 6
  .globl fromhost
fromhost: .dword 0
