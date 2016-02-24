#if 0
#mipsel-none-elf-gcc -G0 -fomit-frame-pointer -Wl,-T,bootrom.ld -Os -nostdlib -o boot.elf bootrom.c && \

# Fuck your ABI flags
#mipsel-none-elf-gcc -G0 -g -Os -fno-toplevel-reorder -fomit-frame-pointer -Wl,-Ttext-segment=0xFE8 -nostdlib -o boot.elf bootrom.c && \

mipsel-none-elf-gcc -G0 -g -O1 -fno-toplevel-reorder -fomit-frame-pointer -Wl,-T,bootrom.ld -nostdlib -o boot.elf bootrom.c && \
mipsel-none-elf-strip -R .MIPS.abiflags boot.elf && \
mipsel-none-elf-objcopy -Obinary boot.elf boot.bin && \
ls -l boot.* &&
true
exit $?
#endif

static void _deleg_start(void);
//void _attribute__((section(".text.startup"))) _very_start(void) {
void _start(void) {
	asm volatile (
		"li $sp, 0xF000"
		: : :);
	_deleg_start();
}

#include <stdint.h>

static char addr_bootdev[64];
static char addr_gpu[64];
static char addr_screen[64];
static char hextab[16] = "0123456789ABCDEF";

static const uint8_t e_ident_match[16+8] = 
	"\x7F" "ELF\x01\x01\x01\x00\x00\x00\x00\x00\x00\x00\x00\x00"
	"\x02\x00\x08\x00\x01\x00\x00\x00";

struct ElfHeader
{
	uint8_t e_ident[16];
	uint16_t e_type, e_machine;
	uint32_t e_version;
	uint32_t e_entry, e_phoff, e_shoff, e_flags;
	uint16_t e_ehsize;
	uint16_t e_phentsize, e_phnum;
	uint16_t e_shentsize, e_shnum;
	uint16_t e_shstrndx;
};

struct ProgHeader
{
	uint32_t p_type;
	uint32_t p_offset;
	uint32_t p_vaddr;
	uint32_t p_paddr;
	uint32_t p_filesz;
	uint32_t p_memsz;
	uint32_t p_flags;
	uint32_t p_align;
};

static int gpu_x = 1;
static int gpu_y = 1;

static void buffer_copy(char *dst, const char *src, int len)
{
	int i;

	for(i = 0; i < len; i++)
		dst[i] = src[i];
}

static void putch(char c)
{
	// use debug port if no GPU
	if(addr_gpu[0] == '\x00')
	{
		*(volatile uint8_t *)0x1FF00004 = c;
		return;
	}

	char addr_tmp[64];
	buffer_copy(addr_tmp, (char *volatile)0x1FF00200, 64);

	if(c == '\n')
	{
		gpu_y++;
		gpu_x = 1;
	} else if(c == '\r') {
		gpu_x = 1;
	} else if(c == '\b') {
		gpu_x--;
		if(gpu_x < 1) gpu_x = 1;

	} else {
		buffer_copy((char *volatile)0x1FF00200, addr_gpu, 64);
		*(volatile char **)0x1FF00280 = "set";
		*(volatile int32_t *)0x1FF00300 = gpu_x;
		*(volatile int32_t *)0x1FF00304 = 6;
		*(volatile int32_t *)0x1FF00308 = gpu_y;
		*(volatile int32_t *)0x1FF0030C = 6;
		char tbuf[2]; tbuf[0] = c; tbuf[1] = '\x00';
		*(volatile char **)0x1FF00310 = tbuf;
		*(volatile int32_t *)0x1FF00314 = 4;
		*(volatile int8_t *)0x1FF00286 = 3;
		gpu_x++;
		if(gpu_x > 80)
		{
			gpu_x = 1;
			gpu_y++;
		}
	} 

	if(gpu_y > 25)
	{
		// TODO: scroll
		gpu_y = 1;
	}

	buffer_copy((char *volatile)0x1FF00200, addr_tmp, 64);
}

static int puthex32(uint32_t v)
{
	int i;

	for(i = 0; i < 8; i++)
		putch(hextab[(v>>((7-i)*4))&15]);
	putch('\n');
}

static int puts(const char *msg)
{
	int i;

	// use debug port if no GPU
	if(addr_gpu[0] == '\x00')
	{
		for(i = 0; msg[i] != '\x00'; i++)
			putch(msg[i]);
		putch('\n');

		return 0;
	}

	char addr_tmp[64];
	buffer_copy(addr_tmp, (char *volatile)0x1FF00200, 64);

	{
		buffer_copy((char *volatile)0x1FF00200, addr_gpu, 64);
		*(volatile char **)0x1FF00280 = "set";
		*(volatile int32_t *)0x1FF00300 = gpu_x;
		*(volatile int32_t *)0x1FF00304 = 6;
		*(volatile int32_t *)0x1FF00308 = gpu_y;
		*(volatile int32_t *)0x1FF0030C = 6;
		*(volatile const char **)0x1FF00310 = msg;
		*(volatile int32_t *)0x1FF00314 = 4;
		*(volatile int8_t *)0x1FF00286 = 3;
		gpu_x = 1;
		gpu_y++;
	} 

	if(gpu_y > 25)
	{
		// TODO: scroll
		gpu_y = 1;
	}

	buffer_copy((char *volatile)0x1FF00200, addr_tmp, 64);

	return 0; // Fuck libc
}

static void __attribute__((noreturn)) panic(const char *msg)
{
	// Print message
	puts(msg);

	// Beep
	// TODO!

	// Lock up
	// (actually jump to null)
	((void (*)(void))0)();

	for(;;) {}
}

static int string_is_equal(const char *restrict a, const char *b)
{
	int i = 0;

	while(a[i] != '\x00' && b[i] != '\x00' && a[i] == b[i])
		i++;
	
	return a[i] == b[i];
}

static int find_device(const char *dtyp)
{
	int i;

	int component_count = *(volatile uint8_t *)0x1FF00284;
	for(i = 0; i < component_count; i++)
	{
		*(volatile uint8_t *)0x1FF00284 = i;
		if(string_is_equal((char *volatile)0x1FF00240, dtyp))
		{
			// found it, now return true
			return 1;
		}
	}

	// failed, return false
	return 0;
}

static void load_file(void *buf, const char *fname)
{
	int ret_count;

	// open file
	*(volatile const char **)0x1FF00280 = "open";
	*(volatile const char **)0x1FF00300 = fname; *(volatile uint32_t *)0x1FF00304 = 4;
	*(volatile const char **)0x1FF00308 = "r"; *(volatile uint32_t *)0x1FF0030C = 4;

	*(volatile int8_t *)0x1FF00286 = 2;
	ret_count = *(volatile int8_t *)0x1FF00286;
	if(ret_count < 1)
	{
		if(*(volatile char *)0x1FF002C0 != '\x00')
			panic((const char *)0x1FF002C0);
		else
			panic("FILE OPEN ERROR");
	}

	int32_t fd_val = *(volatile int32_t *)0x1FF00300;
	int32_t fd_typ = *(volatile int32_t *)0x1FF00304;

	// read file
	void *p = buf;
	uint32_t real_len = 0;
	for(;;)
	{
		*(volatile char **)0x1FF00280 = "read";
		*(volatile int32_t *)0x1FF00300 = fd_val;
		*(volatile int32_t *)0x1FF00304 = fd_typ;
		*(volatile int32_t *)0x1FF00308 = 0x100000;
		*(volatile int32_t *)0x1FF0030C = 6;

		*(volatile int8_t *)0x1FF00286 = 2;
		ret_count = *(volatile int8_t *)0x1FF00286;
		if(ret_count < 1)
		{
			if(*(volatile char *)0x1FF002C0 != '\x00')
				panic((const char *)0x1FF002C0);
			else
				panic("FILE READ ERROR");
		}

		if(*(volatile int32_t *)0x1FF00304 == 0)
			break;

		if(*(volatile int32_t *)0x1FF00304 != 4)
			panic("API FAIL");

		uint32_t block_len = *(volatile uint32_t *)0x1FF00300;
		*(volatile void *volatile*volatile)0x1FF00288 = p;
		*(volatile uint32_t *volatile)0x1FF0028C = block_len;
		*(volatile uint8_t *volatile)0x1FF00287 = 0;
		p += block_len;
		real_len += block_len;
	}

	puts("ELF size");
	puthex32(real_len);

	// close file
	*(volatile char **)0x1FF00280 = "close";
	*(volatile int32_t *)0x1FF00300 = fd_val;
	*(volatile int32_t *)0x1FF00304 = fd_typ;
	*(volatile int8_t *)0x1FF00286 = 1;
}

static void parse_and_run_elf(void *buf)
{
	int i, j;

	puts("Started function");
	if(((uint8_t *)buf)[0] == '\x00')
		panic("FILE DIDN'T LOAD");
	if(((uint8_t *)buf)[0] != '\x7F')
		panic("FILE DIDN'T GET FIRST BYTE");

	puts("Initial ident passed");
	for(i = 0; i < 16+8; i++)
		if(((uint8_t *)buf)[i] != e_ident_match[i])
			panic("ELF MAGIC FAIL");

	puts("Now reading header");
	struct ElfHeader *ehdr = (struct ElfHeader *)buf;
	if(ehdr->e_ehsize != 0x34) panic("ELF FORMAT FAIL");
	if(ehdr->e_phentsize != 0x20) panic("ELF FORMAT FAIL");

	puts("Scanning through program headers");
	for(i = 0; i < ehdr->e_phnum; i++)
	{
		struct ProgHeader *ph = ((struct ProgHeader *)(buf + ehdr->e_phoff + ehdr->e_phentsize))+i;

		if(ph->p_type == 0x00000001) // PT_LOAD
		{
			puts("PT_LOAD found");
			uint8_t *src = (uint8_t *)(buf + ph->p_offset);
			uint8_t *dst = (uint8_t *)(ph->p_vaddr);
			//puthex32(ph->p_offset); puthex32(ph->p_vaddr); puthex32(ph->p_filesz);
			for(j = 0; j < ph->p_filesz; j++)
				dst[j] = src[j];
			for(; j < ph->p_memsz; j++)
				dst[j] = 0;
		}
	}

	puts("Jumping to entry point!");
	puthex32(ehdr->e_entry);

	((void (*)(void))(ehdr->e_entry))();
}

static void _deleg_start(void)
{
	void *elf_base = (void *)0x10000;

	// find GPU + screen
	addr_screen[0] = '\x00';
	addr_gpu[0] = '\x00';

	if(find_device("screen"))
		buffer_copy(addr_screen, (char *volatile)0x1FF00200, 64);
	if(find_device("gpu"))
	{
		buffer_copy(addr_gpu, (char *volatile)0x1FF00200, 64);
		if(addr_screen[0] != '\x00')
		{
			*(volatile char **)0x1FF00280 = "bind";
			*(volatile char **)0x1FF00300 = addr_gpu;
			*(volatile uint32_t *)0x1FF00304 = 4;

			*(volatile int8_t *)0x1FF00286 = 1;
		}
	}

	// find boot device
	puts("Finding boot device");
	if(find_device("filesystem"))
		buffer_copy(addr_bootdev, (char *volatile)0x1FF00200, 64);
	else
		panic("BOOT DEV FAIL");
		
	puts("Boot device:");
	puts(addr_bootdev);

	// load file
	puts("Loading init.elf");
	load_file(elf_base, "init.elf");

	// parse
	puts("Parsing");
	parse_and_run_elf(elf_base);
	panic("BOOT EXITED");
}
