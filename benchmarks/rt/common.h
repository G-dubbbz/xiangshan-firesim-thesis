#ifndef COMMON_H
#define COMMON_H
/* RISC-V replacement for microbench/common.h (upstream uses x86 xchg magic).
 * Shadows the upstream file via -I order; upstream sources stay unmodified. */
#include <stdint.h>
void rt_report(uint64_t cycles, uint64_t instret);
#define ROI_BEGIN() \
  uint64_t _c0, _i0, _c1, _i1; \
  __asm__ __volatile__("fence\n\tcsrr %0, mcycle\n\tcsrr %1, minstret" : "=r"(_c0), "=r"(_i0) :: "memory")
#define ROI_END() \
  __asm__ __volatile__("fence\n\tcsrr %0, mcycle\n\tcsrr %1, minstret" : "=r"(_c1), "=r"(_i1) :: "memory"); \
  rt_report(_c1 - _c0, _i1 - _i0)
#define DETAILED_SIM_STOP() do {} while (0)
#endif
