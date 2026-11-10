CC      = arm-none-eabi-gcc
OBJCOPY = arm-none-eabi-objcopy
SIZE    = arm-none-eabi-size
CFLAGS  = -mcpu=cortex-m0plus -mthumb -Os -Wall -Wextra -ffreestanding \
          -ffunction-sections -fdata-sections
LDFLAGS = -nostartfiles -nostdlib -T stm32l052k8.ld -Wl,--gc-sections -lgcc

all: MIB252_diag.hex

MIB252_diag.elf: main.c stm32l052k8.ld
	$(CC) $(CFLAGS) main.c -o $@ $(LDFLAGS)
	$(SIZE) $@

MIB252_diag.hex: MIB252_diag.elf
	$(OBJCOPY) -O ihex $< $@
	$(OBJCOPY) -O binary $< MIB252_diag.bin

clean:
	rm -f MIB252_diag.elf MIB252_diag.bin MIB252_diag.hex
