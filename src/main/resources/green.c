// just in case whatever you're cross-compiling needs to run actual code
#if !(defined(__i386) || defined(__x86_64))
/*
libgreen: simple userspace library for OCMIPS
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
#include <stdlib.h>
#include <stdint.h>
#include <errno.h>

#include <fcntl.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/times.h>
#include <sys/time.h>

extern char _end[];
extern char _ftext[];
intptr_t xx_cur_brk = (intptr_t)_end;

ssize_t write(int fd, const void *buf, size_t amt)
{
	ssize_t ret;

	asm volatile (
		"li $v0, 4004\n"
		"move $a0, %1\n"
		"move $a1, %2\n"
		"move $a2, %3\n"
		"syscall\n"
		"move %0, $v0\n"
		: "=r"(ret)
		, "+r"(fd), "+r"(buf), "+r"(amt)
		:
		: "v0", "a0", "a1", "a2", "a3"
	);

	return ret;
}

void _exit(int status)
{
	asm volatile (
		"li $v0, 4001\n"
		"move $a0, %0\n"
		"syscall\n"
		: "+r"(status)
		:
		: "v0", "a0", "a1", "a2", "a3"
	);

	for(;;) {}
}

extern int kill(pid_t p, int sig);
int kill(pid_t p, int sig)
{
	errno = EPERM;
	return -1;
}

int open(const char *pathname, int flags, ...)
{
	int ret;

	asm volatile (
		"li $v0, 5\n"
		"move $a0, %1\n"
		"move $a1, %2\n"
		"syscall\n"
		"move %0, $v0\n"
		: "=r"(ret)
		, "+r"(pathname), "+r"(flags)
		:
		: "v0", "a0", "a1", "a2", "a3"
	);

	return ret;
}

int fstat(int fd, struct stat *buf)
{
	//char sbuf[512]; sprintf(sbuf, "fstat = %i\n", fd); write(1, sbuf, strlen(sbuf));
	memset(buf, 0, sizeof(struct stat));
	return 0;
}

ssize_t read(int fd, void *buf, size_t amt)
{
	ssize_t ret;

	asm volatile (
		"li $v0, 4003\n"
		"move $a0, %1\n"
		"move $a1, %2\n"
		"move $a2, %3\n"
		"syscall\n"
		"move %0, $v0\n"
		: "=r"(ret)
		, "+r"(fd), "+r"(buf), "+r"(amt)
		:
		: "v0", "a0", "a1", "a2", "a3"
	);

	return ret;
}

off_t lseek(int fd, off_t offset, int whence)
{
	off_t ret;

	asm volatile (
		"li $a0, 4019\n"
		"move $a0, %1\n"
		"move $a1, %2\n"
		"move $a2, %3\n"
		"syscall\n"
		"move %0, $v0\n"
		: "=r"(ret)
		, "+r"(fd), "+r"(offset), "+r"(whence)
		:
		: "v0", "a0", "a1", "a2", "a3"
	);

	return ret;
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
	int ret;

	asm volatile (
		"li $v0, 4078\n"
		"move $a0, %1\n"
		"move $a1, %2\n"
		"syscall\n"
		"move %0, $v0\n"
		: "=r"(ret)
		, "+r"(tv), "+r"(tz)
		:
		: "v0", "a0", "a1", "a2", "a3"
	);

	return ret;
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
	int ret;

	asm volatile (
		"li $v0, 4006\n"
		"move $a0, %1\n"
		"syscall\n"
		"move %0, $v0\n"
		: "=r"(ret)
		, "+r"(fd)
		:
		: "v0", "a0", "a1", "a2", "a3"
	);

	return ret;
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

/*
int brk(void *pabs)
{
	char sbuf[512]; sprintf(sbuf, "brk = %p\n", pabs); write(1, sbuf, strlen(sbuf));
	return -1;
}
*/

extern int main(int argc, char *argv[]);
extern int _gp[];
void _start(void)
{
	asm volatile (
		//"lui $gp, %%hi(%0)\n"
		//"ori $gp, $gp, %%lo(%0)\n"
		//"addiu $gp, $gp, %%lo(%0)\n"
		"la $gp, %0\n"
		:
		: "i"(_gp)
		:
	);

	char *argv_base[2] = {
		"init",
		NULL
	};

	volatile int r = main(1, argv_base);
	_exit(r);
}

#endif

