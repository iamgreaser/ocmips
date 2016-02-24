/*
liblabour: simple kernel library for OCMIPS
recommended to be paired up with newlib

Copyright (c) 2016 Ben "GreaseMonkey" Russell

This software is provided 'as-is', without any express or implied warranty.
In no event will the authors be held liable for any damages arising from the
use of this software.

Permission is granted to anyone to use this software for any purpose, including
commercial applications, and to alter it and redistribute it freely, subject to
the following restrictions:

    1. The origin of this software must not be misrepresented; you must not
       claim that you wrote the original software. If you use this software in
       a product, an acknowledgment in the product documentation would be
       appreciated but is not required.

    2. Altered source versions must be plainly marked as such, and must not be
       misrepresented as being the original software.

    3. This notice may not be removed or altered from any source distribution.

*/
#include <string.h>
#include <stdio.h>
#include <stdint.h>
#include <errno.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/times.h>
#include <sys/time.h>
#include <unistd.h>

/*int internal_errno;
int *__errno _PARAMS ((void))
{
	return &internal_errno;
}*/

extern char _end[];
extern char _ftext[];
intptr_t xx_cur_brk = (intptr_t)_end;

int gpu_x = 0;
int gpu_y = 0;
int gpu_w = 80;
int gpu_h = 25;
#define GPU_BUF_MAX 256
int gpu_outbuf_len = 0;
volatile char gpu_outbuf[GPU_BUF_MAX];
volatile char gpu_address[64];

#define KB_BUF_MAX 256
int kb_inbuf_len = 0;
int kb_inbuf_needs_flush = 0;
int kb_inbuf_flushed = 0;
char kb_inbuf[KB_BUF_MAX];

int last_key_char = -1;
int last_key_code = -1;
char *clipbuf = NULL;
int clipbuf_len = 0;

#define STYP_NUL 0
#define STYP_BOL 2
#define STYP_STR 4
#define STYP_INT 6
#define STYP_FLT 8
#define STYP_HDL 10

#define SYS_ARG_TYP(n) (*(volatile int32_t *)(0x1FF00304+(n)*8))
#define SYS_ARG_INT(n) (*(volatile int32_t *)(0x1FF00300+(n)*8))
#define SYS_ARG_FLT(n) (*(volatile float *)(0x1FF00300+(n)*8))
#define SYS_ARG_STR(n) (*(volatile const char **)(0x1FF00300+(n)*8))

const char *mclib_find_device(const char *dtyp)
{
	int i, j;

	int component_count = *(volatile uint8_t *)0x1FF00284;
	for(i = 0; i < component_count; i++)
	{
		*(volatile uint8_t *)0x1FF00284 = i;
		if(!strcmp((char *volatile)0x1FF00240, dtyp))
		{
			// found it, now return true
			return (const char *volatile)0x1FF00200;
		}
	}

	// failed, return false
	return NULL;
}

int mclib_gpu_get_resolution(int *w, int *h)
{
	char addr_tmp[64];
	memcpy(addr_tmp, (uint8_t *volatile)0x1FF00200, 64);
	memcpy((uint8_t *volatile)0x1FF00200, gpu_address, 64);
	*(volatile char *volatile*volatile)0x1FF00280 = "getResolution";
	*(volatile uint8_t *)0x1FF00286 = 0;
	memcpy((uint8_t *volatile)0x1FF00200, addr_tmp, 64);

	if(*(volatile uint8_t *)0x1FF00286 < 2)
		return -1;
	if(SYS_ARG_TYP(0) != 6)
		return -1;
	if(SYS_ARG_TYP(0) != 6)
		return -1;

	*w = SYS_ARG_INT(0);
	*h = SYS_ARG_INT(1);

	return 0;
}

void mclib_gpu_fill(int x, int y, int w, int h, const char *cs)
{
	char addr_tmp[64];
	memcpy(addr_tmp, (uint8_t *volatile)0x1FF00200, 64);
	memcpy((uint8_t *volatile)0x1FF00200, gpu_address, 64);
	*(volatile char *volatile*volatile)0x1FF00280 = "fill";
	SYS_ARG_INT(0) = x; SYS_ARG_TYP(0) = STYP_INT;
	SYS_ARG_INT(1) = y; SYS_ARG_TYP(1) = STYP_INT;
	SYS_ARG_INT(2) = w; SYS_ARG_TYP(2) = STYP_INT;
	SYS_ARG_INT(3) = h; SYS_ARG_TYP(3) = STYP_INT;
	SYS_ARG_STR(4) = cs; SYS_ARG_TYP(4) = STYP_STR;
	*(volatile uint8_t *)0x1FF00286 = 5;
	memcpy((uint8_t *volatile)0x1FF00200, addr_tmp, 64);
}

void mclib_gpu_copy(int x, int y, int w, int h, int dx, int dy)
{
	char addr_tmp[64];
	memcpy(addr_tmp, (uint8_t *volatile)0x1FF00200, 64);
	memcpy((uint8_t *volatile)0x1FF00200, gpu_address, 64);
	*(volatile char *volatile*volatile)0x1FF00280 = "copy";
	SYS_ARG_INT(0) = x; SYS_ARG_TYP(0) = STYP_INT;
	SYS_ARG_INT(1) = y; SYS_ARG_TYP(1) = STYP_INT;
	SYS_ARG_INT(2) = w; SYS_ARG_TYP(2) = STYP_INT;
	SYS_ARG_INT(3) = h; SYS_ARG_TYP(3) = STYP_INT;
	SYS_ARG_INT(4) = dx; SYS_ARG_TYP(4) = STYP_INT;
	SYS_ARG_INT(5) = dy; SYS_ARG_TYP(5) = STYP_INT;
	*(volatile uint8_t *)0x1FF00286 = 6;
	memcpy((uint8_t *volatile)0x1FF00200, addr_tmp, 64);
}

void mclib_gpu_set(int x, int y, const volatile char *s)
{
	char addr_tmp[64];
	memcpy(addr_tmp, (uint8_t *volatile)0x1FF00200, 64);
	memcpy((uint8_t *volatile)0x1FF00200, gpu_address, 64);
	*(volatile char *volatile*volatile)0x1FF00280 = "set";
	SYS_ARG_INT(0) = x; SYS_ARG_TYP(0) = STYP_INT;
	SYS_ARG_INT(1) = y; SYS_ARG_TYP(1) = STYP_INT;
	SYS_ARG_STR(2) = s; SYS_ARG_TYP(2) = STYP_STR;
	*(volatile uint8_t *)0x1FF00286 = 3;
	memcpy((uint8_t *volatile)0x1FF00200, addr_tmp, 64);
}

static void gpu_flush(void)
{
	if(gpu_outbuf_len > 0)
	{
		gpu_outbuf[gpu_outbuf_len] = '\x00';
		mclib_gpu_set(gpu_x+1, gpu_y+1, gpu_outbuf);
		// TODO: unicode length
		gpu_x += gpu_outbuf_len;
		gpu_outbuf_len = 0;
	}

	// TODO: proper wrapping (as opposed to truncation)

	if(gpu_x >= gpu_w)
	{
		gpu_x = 0;
		gpu_y++;
	}

	if(gpu_y >= gpu_h)
	{
		mclib_gpu_copy(1, 2, gpu_w, gpu_h-1, 0, -1);
		mclib_gpu_fill(1, gpu_h, gpu_w, 1, " ");
		gpu_y = gpu_h-1;
	}
}

ssize_t write(int fd, const void *buf, size_t amt)
{
	size_t i;

	if(fd == 1 || fd == 2)
	{
		for(i = 0; i < amt; i++)
		{
			uint8_t c = ((uint8_t *)buf)[i];
			if(c == '\n')
			{
				gpu_flush();
				gpu_x = 0;
				gpu_y++;
				gpu_flush();

			} else if(c == '\r') {
				gpu_flush();
				gpu_x = 0;
				gpu_flush();


			} else if(c == '\t') {
				// tabstop of 4 so the damn thing will fit nicely
				// (even though 8 is more standard)
				gpu_flush();
				gpu_x = (gpu_x+4)&~3;
				gpu_flush();

			} else if(c == '\b') {
				if(gpu_outbuf_len >= 1)
				{
					gpu_outbuf_len--;
				} else {
					gpu_flush();
					gpu_x--;
					if(gpu_x <= 0) gpu_x = 0;
					if(gpu_outbuf_len < GPU_BUF_MAX-1)
						gpu_outbuf[gpu_outbuf_len++] = ' ';
					gpu_flush();
					gpu_x--;
					gpu_flush();
				}

			} else {
				if(gpu_outbuf_len < GPU_BUF_MAX-1)
					gpu_outbuf[gpu_outbuf_len++] = c;
			}
		}

		gpu_flush();

		return amt;
	}

	// TODO!
	return amt;
}

void _exit(int status)
{
	char sbuf[512]; sprintf(sbuf, "exit = %i\n", status); write(1, sbuf, strlen(sbuf));
	volatile int r = ((int (*)(void))(0x00000000))();
	(void)r;
	for(;;) {}
}

extern int kill(pid_t p, int sig);
int kill(pid_t p, int sig)
{
	errno = EPERM;
	return -1;

}

int open(const char *pathname, int flags)
{
	//char sbuf[512]; sprintf(sbuf, "fname = \"%s\", flags = %i\n", pathname, flags); write(1, sbuf, strlen(sbuf));
	errno = EACCES;
	return -1;
}

int fstat(int fd, struct stat *buf)
{
	//char sbuf[512]; sprintf(sbuf, "fstat = %i\n", fd); write(1, sbuf, strlen(sbuf));
	memset(buf, 0, sizeof(struct stat));
	return 0;
}

int poll_event(void)
{
	int ev_args = *(volatile int8_t *)0x1FF00287;
	if(ev_args <= 0) return 0;

	volatile char signame[64];
	int buflen = SYS_ARG_INT(0);
	if(buflen > 63) buflen = 63;
	*(volatile int32_t *)0x1FF00288 = signame;
	*(volatile int32_t *)0x1FF0028C = buflen;
	signame[0] = '\x00';
	*(volatile int8_t *)0x1FF00287 = 0;
	signame[buflen] = '\x00';

	if(!strcmp(signame, "key_down"))
	{
		if(SYS_ARG_TYP(2) == STYP_FLT) last_key_char = (int)(SYS_ARG_FLT(2));
		else last_key_char = SYS_ARG_INT(2);

		if(SYS_ARG_TYP(3) == STYP_FLT) last_key_code = (int)(SYS_ARG_FLT(3));
		else last_key_code = SYS_ARG_INT(3);

	} else {
		// TODO: clipboards

	}

	return ev_args;

}

ssize_t read(int fd, void *buf, size_t amt)
{
	int i;
	//char sbuf[512]; sprintf(sbuf, "read = %i, amt = %i\n", fd, amt); write(1, sbuf, strlen(sbuf));
	char tbuf[2];

	if(fd == 0)
	{
		if(amt == 0) return 0;
		for(;;)
		{
			while((!kb_inbuf_needs_flush) && poll_event() > 0)
			{
				// TODO: handle left,right,up,down,etc
				// TODO: decode UTF-8
				if(last_key_char > 0 && last_key_char <= 127)
				{
					if(last_key_char == '\b')
					{
						if(kb_inbuf_len > 0)
						{
							kb_inbuf_len--;
							tbuf[0] = '\b';
							// local echo - TODO: have a flag for this
							write(1, tbuf, 1);
						}

						last_key_char = -1;
						last_key_code = -1;
						continue;
					}

					if(last_key_char == '\r')
					{
						last_key_char = '\n';
						kb_inbuf_needs_flush = 1;
						kb_inbuf_flushed = 0;
					}

					if(kb_inbuf_len < KB_BUF_MAX-1)
					{
						kb_inbuf[kb_inbuf_len++] = (char)last_key_char;
						tbuf[0] = (char)last_key_char;
						// local echo - TODO: have a flag for this
						write(1, tbuf, 1);
					}

					last_key_char = -1;
					last_key_code = -1;
				}
			}

			if(kb_inbuf_needs_flush)
			{
				int to_ret = kb_inbuf_len - kb_inbuf_flushed;
				if(to_ret <= amt)
					kb_inbuf_needs_flush = 0;
				else
					to_ret = amt;

				memcpy(buf, kb_inbuf + kb_inbuf_flushed, to_ret);
				kb_inbuf_flushed += to_ret;
				kb_inbuf_len = 0;
				return to_ret;
			}

			// Sleep a bit
			*(volatile uint32_t *)0x1FF00020 = 1;
			continue;
		}
	}

	return 0;
}

off_t lseek(int fd, off_t offset, int whence)
{
	// TODO!
	//char sbuf[512]; sprintf(sbuf, "lseek = %i, off = %i, whence = %i\n", fd, offset, whence); write(1, sbuf, strlen(sbuf));
	return offset;
}

int isatty(int fd)
{
	//char sbuf[512]; sprintf(sbuf, "isatty = %i\n", fd); write(1, sbuf, strlen(sbuf));
	return (fd >= 0 && fd <= 2);
}

extern pid_t getpid(void);
pid_t getpid(void)
{
	//char sbuf[512]; sprintf(sbuf, "getpid\n"); write(1, sbuf, strlen(sbuf));
	return 999;
}

int gettimeofday(struct timeval *restrict tv, void *restrict tz)
{
	if(tv != NULL)
	{
		// Fetch time
		uint32_t wallclock_high = *(volatile uint32_t *)0x1FF00024;
		uint32_t wallclock_low = *(volatile uint32_t *)0x1FF00020;

		// If the upper bits don't match, fetch it again!
		if((wallclock_high<<20) != (wallclock_low&~((1<<20-1))))
		{
			wallclock_high = *(volatile uint32_t *)0x1FF00024;
			wallclock_low = *(volatile uint32_t *)0x1FF00020;
		}

		// Output the value
		uint64_t wallclock = ((((uint64_t)wallclock_high)<<(uint64_t)20)
			| (((uint64_t)wallclock_low)&(uint64_t)((1<<20-1))));
		tv->tv_sec = wallclock/(uint64_t)1000000;
		tv->tv_usec = ((uint32_t)(wallclock%(uint64_t)1000000));
	}

	return 0;
}

clock_t times(struct tms *buf)
{
	struct timeval tv = {.tv_sec = 0, .tv_usec = 0};
	gettimeofday(&tv, NULL);

	// TODO: use sysconf to get tick granularity
	buf->tms_utime = tv.tv_sec*1000+(tv.tv_usec/1000);
	buf->tms_stime = 0;
	buf->tms_cutime = buf->tms_utime;
	buf->tms_cstime = 0;

	return 0;
}

int unlink(const char *pathname)
{
	errno = EACCES;
	return -1;
}

int link(const char *oldpath, const char *newpath)
{
	errno = EPERM;
	return -1;
}

int close(int fd)
{
	//char sbuf[512]; sprintf(sbuf, "close = %i\n", fd); write(1, sbuf, strlen(sbuf));
	return 0;
}

void *sbrk(intptr_t increment)
{
	/*
	if(xx_cur_brk == 0)
	{
		char sbuf[512]; sprintf(sbuf, "BUG: brk is 0, fix linker/loader!\n");
		write(1, sbuf, strlen(sbuf));
		xx_cur_brk = (intptr_t)_end;
	}
	if(xx_cur_brk == 0)
	{
		char sbuf[512]; sprintf(sbuf, "BUG: brk is still 0, fix linker/loader!\n");
		write(1, sbuf, strlen(sbuf));
		xx_cur_brk = (intptr_t)0x100000;
	}
	*/
	char *oldbrk = (char *)xx_cur_brk;
	xx_cur_brk += increment;
	intptr_t tbrk = (intptr_t)xx_cur_brk;
	//tbrk = (tbrk+0xFFF)&~0xFFF;

#if 0
	char sbuf[512]; sprintf(sbuf, "RAM usage: %iKB heap %iKB total %iKB from start - break is %p\n"
		, (((char *)xx_cur_brk-_end)+512)>>10
		, (((char *)xx_cur_brk-_ftext)+512)>>10
		, (tbrk+512)>>10
		, (char *)xx_cur_brk
		); write(1, sbuf, strlen(sbuf));
#endif
	return (void *)oldbrk;
}

int brk(void *pabs)
{
	char sbuf[512]; sprintf(sbuf, "brk = %p\n", pabs); write(1, sbuf, strlen(sbuf));
	return -1;
}

extern int main(int argc, char *argv[]);
extern int _gp[];
void _start(void)
{
	asm volatile (
		"lui $gp, %%hi(%0)\n"
		"addiu $gp, $gp, %%lo(%0)\n"
		:
		: "i"(_gp)
		: 
	);

	char *argv_base[2] = {
		"shitlib_launcher",
		NULL
	};

	const char *gpudev = mclib_find_device("gpu");
	if(gpudev == NULL)
	{
		*(volatile uint8_t *)0x1FF00004 = ';';
		*(volatile uint8_t *)0x1FF00004 = '_';
		*(volatile uint8_t *)0x1FF00004 = ';';
		*(volatile uint8_t *)0x1FF00004 = '\n';
		for(;;) {}
	}

	memcpy(gpu_address, gpudev, 64);
	int i;
	for(i = 0; gpu_address[i] != '\x00'; i++)
		*(volatile uint8_t *)0x1FF00004 = gpu_address[i];
	*(volatile uint8_t *)0x1FF00004 = '\n';

	// clear screen
	mclib_gpu_fill(1, 1, gpu_w, gpu_h, " ");
	mclib_gpu_get_resolution(&gpu_w, &gpu_h);
	//sbrk(0);
	volatile int r = main(1, argv_base);

	(void)r;
}

