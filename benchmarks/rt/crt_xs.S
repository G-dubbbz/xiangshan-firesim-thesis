# Startup for XiangShan in Chipyard: trap handler, clear MDT, enable FPU,
# gp/sp, zero .bss, main(1, 0), tohost = 1. Trap: mcause/mepc/mtval -> UART0 ie, tohost = 3.
  .section .text.init
  .globl _start
_start:
  la    t0, trap
  csrw  mtvec, t0
  li    t0, 1
  slli  t0, t0, 42
  csrc  mstatus, t0          # clear mstatus.MDT (set at reset)
  li    t0, 0x6000
  csrs  mstatus, t0          # mstatus.FS = dirty (FP kernels)
  csrwi fcsr, 0
  .option push
  .option norelax
  la    gp, __global_pointer$
  .option pop
  la    sp, _stack_top
  la    t0, __bss_start
  la    t1, _end
1:bgeu  t0, t1, 2f
  sd    zero, 0(t0)
  addi  t0, t0, 8
  j     1b
2:li    a0, 1
  li    a1, 0
  call  main
  li    t0, 0x08000000
  li    t1, 1
  sd    t1, 0(t0)            # tohost = 1 -> PASS
3:j     3b
  .align 2
trap:
  li    t0, 0x10020010
  csrr  t1, mcause
  sw    t1, 0(t0)
  csrr  t1, mepc
  sw    t1, 0(t0)
  csrr  t1, mtval
  sw    t1, 0(t0)
  li    t0, 0x08000000
  li    t1, 3
  sd    t1, 0(t0)            # tohost = 3 -> FAIL (code 1)
4:j     4b
