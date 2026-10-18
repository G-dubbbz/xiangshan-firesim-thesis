/* Report backend for XiangShan in Chipyard. HTIF syscalls are unusable (DRAM
 * is cached inside XSTop, fesvr reads stale memory), so counters are written to
 * UART0 'ie' and read from the [XSPROBE] 'periph W' lines. Tag 0x524F4931 = "ROI1". */
#include <stdint.h>
#include <stddef.h>
#define UART_IE (*(volatile uint32_t *)0x10020010)
#define TOHOST  (*(volatile uint64_t *)0x08000000)
void rt_report(uint64_t cyc, uint64_t ins) {
  UART_IE = 0x524F4931u;
  UART_IE = (uint32_t)cyc; UART_IE = (uint32_t)(cyc >> 32);
  UART_IE = (uint32_t)ins; UART_IE = (uint32_t)(ins >> 32);
  UART_IE = 0;
}
extern char _heap_start[];
static char *heap_ptr = _heap_start;
void *_sbrk(ptrdiff_t inc) { char *p = heap_ptr; heap_ptr += inc; return p; }
void _exit(int code) { TOHOST = ((uint64_t)code << 1) | 1; for (;;) ; }
