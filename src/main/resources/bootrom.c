#if 0
#mipsel-none-elf-gcc -G0 -fomit-frame-pointer -Wl,-T,bootrom.ld -Os -nostdlib -o boot.elf bootrom.c && \
#mipsel-none-elf-gcc -G0 -g -Os -fno-toplevel-reorder -fomit-frame-pointer -Wl,-Ttext-segment=0xFE8 -nostdlib -o boot.elf bootrom.c && \

mipsel-none-elf-gcc -G0 -g -O1 -fno-toplevel-reorder -fomit-frame-pointer -Wl,-T,bootrom.ld -nostdlib -o boot.elf bootrom.c && \
mipsel-none-elf-objcopy -Obinary boot.elf boot.bin && \
ls -l boot.* &&
true
exit $?
#endif

#include <stdint.h>

#define STYP_NUL 0
#define STYP_BOL 2
#define STYP_STR 4
#define STYP_INT 6
#define STYP_FLT 8
#define STYP_HDL 10

#define SYS_ARG_TYP(n) (*(volatile int32_t *)(0xBFF00304+(n)*8))
#define SYS_ARG_INT(n) (*(volatile int32_t *)(0xBFF00300+(n)*8))
#define SYS_ARG_FLT(n) (*(volatile float *)(0xBFF00300+(n)*8))
#define SYS_ARG_STR(n) (*(volatile const char **)(0xBFF00300+(n)*8))

static void _deleg_start(void);
void _start(void) {
	asm volatile (
		"lui $sp, 0xA000\n"
		"ori $sp, $sp, 0x4000\n"
		: : :);

	// set up UTLB handler
	*(volatile uint32_t *)0xBFC00100 = 0x401A4000; // MFC0  k0, c0_vaddr
	*(volatile uint32_t *)0xBFC00104 = 0x001AD302; // SRL   k0, k0, 12
	*(volatile uint32_t *)0xBFC00108 = 0x001AD300; // SLL   k0, k0, 12
	*(volatile uint32_t *)0xBFC0010C = 0x409A5000; // MTC0  k0, c0_entryhi
	*(volatile uint32_t *)0xBFC00110 = 0x375A0700; // ORI   k0, k0, 0x0700
	*(volatile uint32_t *)0xBFC00114 = 0x409A1000; // MTC0  k0, c0_entrylo
	*(volatile uint32_t *)0xBFC00118 = 0x42000006; // TLBWR
	*(volatile uint32_t *)0xBFC0011C = 0x00000000; // NOP
	*(volatile uint32_t *)0xBFC00120 = 0x00000000; // NOP
	*(volatile uint32_t *)0xBFC00124 = 0x00000000; // NOP
	*(volatile uint32_t *)0xBFC00128 = 0x401A7000; // MFC0  k0, c0_epc
	*(volatile uint32_t *)0xBFC0012C = 0x03400008; // JR    k0
	*(volatile uint32_t *)0xBFC00130 = 0x42000010; // RFE

	_deleg_start();
}

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
		*(volatile uint8_t *)0xBFF00004 = c;
		return;
	}

	char addr_tmp[64];
	buffer_copy(addr_tmp, (char *volatile)0xBFF00200, 64);

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
		buffer_copy((char *volatile)0xBFF00200, addr_gpu, 64);
		*(volatile char **)0xBFF00280 = "set";
		SYS_ARG_INT(0) = gpu_x; SYS_ARG_TYP(0) = STYP_INT;
		SYS_ARG_INT(1) = gpu_y; SYS_ARG_TYP(1) = STYP_INT;
		char tbuf[2]; tbuf[0] = c; tbuf[1] = '\x00';
		SYS_ARG_STR(2) = tbuf; SYS_ARG_TYP(2) = STYP_STR;
		*(volatile int8_t *)0xBFF00286 = 3;
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

	buffer_copy((char *volatile)0xBFF00200, addr_tmp, 64);
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
	buffer_copy(addr_tmp, (char *volatile)0xBFF00200, 64);

	{
		buffer_copy((char *volatile)0xBFF00200, addr_gpu, 64);
		*(volatile char **)0xBFF00280 = "set";
		SYS_ARG_INT(0) = gpu_x; SYS_ARG_TYP(0) = STYP_INT;
		SYS_ARG_INT(1) = gpu_y; SYS_ARG_TYP(1) = STYP_INT;
		SYS_ARG_STR(2) = msg; SYS_ARG_TYP(2) = STYP_STR;
		*(volatile int8_t *)0xBFF00286 = 3;
		gpu_x = 1;
		gpu_y++;
	} 

	if(gpu_y > 25)
	{
		// TODO: scroll
		gpu_y = 1;
	}

	buffer_copy((char *volatile)0xBFF00200, addr_tmp, 64);

	return 0;
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

static int find_device(const char *dtyp, int from)
{
	int i;
	static int component_count = 0;

	if(from < 0)
	{
		component_count = *(volatile uint8_t *)0xBFF00284;
		from = 0;
	}

	for(i = from; i < component_count; i++)
	{
		*(volatile uint8_t *)0xBFF00284 = i;
		if(string_is_equal((char *volatile)0xBFF00240, dtyp))
		{
			// found it, now return true
			return i+1;
		}
	}

	// failed, return false
	return 0;
}

static int file_seek(int32_t fd_val, int32_t fd_typ, void *whence, int32_t offs)
{
	*(volatile char **)0xBFF00280 = "seek";
	SYS_ARG_INT(0) = fd_val; SYS_ARG_TYP(0) = fd_typ;
	SYS_ARG_STR(1) = whence; SYS_ARG_TYP(1) = STYP_STR;
	SYS_ARG_INT(2) = offs; SYS_ARG_TYP(2) = STYP_INT;
	*(volatile int8_t *)0xBFF00286 = 3;
	int ret_count = *(volatile int8_t *)0xBFF00286;
	if(ret_count < 1)
	{
		if(*(volatile char *)0xBFF002C0 != '\x00')
			panic((const char *)0xBFF002C0);
		else
			panic("FILE READ ERROR");
	}

	return (SYS_ARG_TYP(0)==8 ? (int)SYS_ARG_FLT(0) : SYS_ARG_INT(0));
}

static int file_read(int32_t fd_val, int32_t fd_typ, void *buf, int32_t len)
{
	void *p = buf;
	int32_t real_len = 0;
	int32_t remain_len = len;
	while(remain_len > 0)
	{
		*(volatile char **)0xBFF00280 = "read";
		SYS_ARG_INT(0) = fd_val; SYS_ARG_TYP(0) = fd_typ;
		SYS_ARG_INT(1) = remain_len; SYS_ARG_TYP(1) = STYP_INT;

		*(volatile int8_t *)0xBFF00286 = 2;
		int ret_count = *(volatile int8_t *)0xBFF00286;
		if(ret_count < 1)
		{
			if(*(volatile char *)0xBFF002C0 != '\x00')
				panic((const char *)0xBFF002C0);
			else
				panic("FILE READ ERROR");
		}

		if(SYS_ARG_TYP(0) == STYP_NUL)
			break;

		if(SYS_ARG_TYP(0) != STYP_STR)
			panic("API FAIL");

		uint32_t block_len = SYS_ARG_INT(0);
		*(volatile void *volatile*volatile)0xBFF00288 = p;
		*(volatile uint32_t *volatile)0xBFF0028C = block_len;
		*(volatile uint8_t *volatile)0xBFF00287 = 0;
		p += block_len;
		real_len += block_len;
		remain_len -= block_len;
	}

	return real_len;
}

static void load_parse_and_run_elf(const char *fname)
{
	struct ElfHeader ehdr;
	int i, j;

	int from = -1;
	for(;;)
	{
		// find boot device
		from = find_device("filesystem", from);
		if(from > 0)
			buffer_copy(addr_bootdev, (char *volatile)0xBFF00200, 64);
		else
			panic("BOOT DEV FAIL");

		puts("Boot device:");
		puts(addr_bootdev);

		// open file
		*(volatile const char **)0xBFF00280 = "open";
		SYS_ARG_STR(0) = fname; SYS_ARG_TYP(0) = 4;
		SYS_ARG_STR(1) = "rb"; SYS_ARG_TYP(1) = 4;
		*(volatile int8_t *)0xBFF00286 = 2;
		int ret_count = *(volatile int8_t *)0xBFF00286;
		if(ret_count < 1 || SYS_ARG_TYP(0) == 0)
		{
			if(*(volatile char *)0xBFF002C0 != '\x00')
				puts((const char *)0xBFF002C0);
			else
				puts("FILE OPEN ERROR");

			continue;
		}

		break;
	}

	int32_t fd_val = SYS_ARG_INT(0);
	int32_t fd_typ = SYS_ARG_TYP(0);

	// read header
	if(file_read(fd_val, fd_typ, &ehdr, sizeof(struct ElfHeader)) != sizeof(struct ElfHeader))
		panic("FILE SIZE ERROR");

	for(i = 0; i < 16+8; i++)
		if(((uint8_t *)&ehdr)[i] != e_ident_match[i])
			panic("ELF MAGIC FAIL");

	puts("Now reading header");
	if(ehdr.e_ehsize != 0x34) panic("ELF FORMAT FAIL");
	if(ehdr.e_phentsize != 0x20) panic("ELF FORMAT FAIL");

	puts("Scanning through program headers");
	for(i = 0; i < ehdr.e_phnum; i++)
	{
		struct ProgHeader ph;

		file_seek(fd_val, fd_typ, "set", ehdr.e_phoff + ehdr.e_phentsize*i);
		file_read(fd_val, fd_typ, &ph, sizeof(struct ProgHeader));

		if(ph.p_type == 0x00000001) // PT_LOAD
		{
			puts("PT_LOAD");
			uint8_t *dst = (uint8_t *)(ph.p_vaddr);
			file_seek(fd_val, fd_typ, "set", ph.p_offset);
			j = file_read(fd_val, fd_typ, dst, ph.p_filesz);
			for(; j < ph.p_memsz; j++)
				dst[j] = 0;
		}
	}

	// close file
	*(volatile char **)0xBFF00280 = "close";
	SYS_ARG_INT(0) = fd_val; SYS_ARG_TYP(0) = fd_typ;
	*(volatile int8_t *)0xBFF00286 = 1;

	puts("Jumping to entry point!");
	puthex32(ehdr.e_entry);

	// copy boot address to MMIO address
	for(i = 0; i < 64; i++)
		((volatile uint8_t *)0xBFF00200)[i] = addr_bootdev[i];

	// boot
	((void (*)(void))(ehdr.e_entry))();
}

static void _deleg_start(void)
{
	// find GPU + screen
	addr_screen[0] = '\x00';
	addr_gpu[0] = '\x00';

	if(find_device("screen", -1))
		buffer_copy(addr_screen, (char *volatile)0xBFF00200, 64);
	if(find_device("gpu", -1))
	{
		buffer_copy(addr_gpu, (char *volatile)0xBFF00200, 64);
		if(addr_screen[0] != '\x00')
		{
			*(volatile char **)0xBFF00280 = "bind";
			SYS_ARG_STR(0) = addr_gpu; SYS_ARG_TYP(0) = STYP_STR;

			*(volatile int8_t *)0xBFF00286 = 1;
		}
	}

	// load file
	puts("Loading init.elf");
	load_parse_and_run_elf("init.elf");
	panic("BOOT EXITED");
}
