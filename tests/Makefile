# Build bare-metal smoke tests for the XiangShan Chipyard tile.
# Programs are linked at 0x80000000 (the tile's reset vector) and preloaded with LOADMEM=1.

CROSS   ?= riscv64-unknown-elf-
CC      := $(CROSS)gcc
CFLAGS  := -march=rv64gc -mabi=lp64 -static -nostdlib -nostartfiles -T link.ld

all: xs-hello.elf xs-exit.elf

xs-hello.elf: hello.S link.ld
	$(CC) $(CFLAGS) -o $@ hello.S
	@$(CROSS)readelf -lW $@ | grep LOAD
	@if $(CROSS)readelf -lW $@ | awk '/LOAD/ {print $$3}' | grep -q "^0x00000000[0-7]"; then \
	  echo "ERROR: a LOAD segment is below 0x80000000 (+loadmem would segfault)"; exit 1; fi

xs-exit.elf: exit.S link.ld
	$(CC) $(CFLAGS) -o $@ exit.S
	@$(CROSS)readelf -lW $@ | grep LOAD

clean:
	rm -f xs-hello.elf xs-exit.elf

.PHONY: all clean
