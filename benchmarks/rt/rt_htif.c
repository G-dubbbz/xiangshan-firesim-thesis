/* Report backend for Spike / Rocket / BOOM (libgloss HTIF). */
#include <stdio.h>
#include <stdint.h>
void rt_report(uint64_t cyc, uint64_t ins) {
  printf("ROI cycles=%lu instret=%lu\n", (unsigned long)cyc, (unsigned long)ins);
}
