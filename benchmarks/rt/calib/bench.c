/* Calibration of the mcycle/minstret ROI measurement. Expected instret deltas vs empty ROI:
 * addi: +1000, fuse: +1000 (500 slli/srli pairs; candidates for XiangShan fusion). */
#include "common.h"
__attribute__((noinline)) void roi_empty(void) { ROI_BEGIN(); ROI_END(); }
__attribute__((noinline)) void roi_addi(void) { ROI_BEGIN();
  __asm__ __volatile__(".rept 1000\n\taddi t0, t0, 1\n\t.endr" ::: "t0"); ROI_END(); }
__attribute__((noinline)) void roi_fuse(void) { ROI_BEGIN();
  __asm__ __volatile__(".rept 500\n\tslli t0, t0, 32\n\tsrli t0, t0, 32\n\t.endr" ::: "t0"); ROI_END(); }
int main(void) { roi_empty(); roi_addi(); roi_fuse(); return 0; }
