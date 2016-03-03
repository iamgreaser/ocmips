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
#include <stdlib.h>
#include <stdint.h>
#include <errno.h>

#include <fcntl.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/times.h>
#include <sys/time.h>

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
char gpu_outbuf[GPU_BUF_MAX];
char gpu_address[64];

#define KB_BUF_MAX 256
int kb_inbuf_len = 0;
int kb_inbuf_needs_flush = 0;
int kb_inbuf_flushed = 0;
char kb_inbuf[KB_BUF_MAX];

#define DRIVE_MAX 32
#define FILE_MAX 32
char fs_address[DRIVE_MAX][40];
char fs_mount_point[DRIVE_MAX][40];
char fs_open_address[FILE_MAX][40];
int fs_open_index[FILE_MAX];
int fs_fdtyp = 0;

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

#define SYS_ARG_TYP(n) (*(volatile int32_t *)(0xBFF00304+(n)*8))
#define SYS_ARG_INT(n) (*(volatile int32_t *)(0xBFF00300+(n)*8))
#define SYS_ARG_FLT(n) (*(volatile float *)(0xBFF00300+(n)*8))
#define SYS_ARG_STR(n) (*(volatile const char **)(0xBFF00300+(n)*8))

const char *mclib_find_device(const char *dtyp)
{
	int i, j;

	int component_count = *(volatile uint8_t *)0xBFF00284;
	for(i = 0; i < component_count; i++)
	{
		*(volatile uint8_t *)0xBFF00284 = i;
		if(!strcmp((char *volatile)0xBFF00240, dtyp))
		{
			// found it, now return true
			return (const char *volatile)0xBFF00200;
		}
	}

	// failed, return false
	return NULL;
}

// Note, requires an ABSOLUTE, CORRECTED path!
// (that is, /mnt//abc/ needs correction)
int lookup_mount_path(const char *inpath, const char **outaddr, const char **outpath)
{
	int i;
	int fsidx = -1;

	// Scan mount table
	for(i = 0; i < DRIVE_MAX; i++)
	{
		if(fs_address[i][0] != 0 && fs_mount_point[i][0] != 0)
		{
			int mplen = strlen(fs_mount_point[i]);

			if(!strncmp(fs_mount_point[i], inpath, mplen))
			{
				fsidx = i;
				*outaddr = fs_address[i];
				*outpath = inpath + mplen;
			}
		}
	}

	// Fail if we couldn't find it
	if(fsidx == -1)
	{
		errno = ENOENT;
		return -1;
	}

	return 0;
}

ssize_t get_abs_correct_path(char *dst, const char *src, const char *cwd, size_t dst_len)
{
	ssize_t doffs = 0;
	ssize_t soffs = 0;

	// Is this path absolute?
	if(src[0] != '/' && cwd != NULL)
	{
		// Ensure cwd is valid
		if(cwd[0] != '/')
		{
			errno = EINVAL;
			return -1;
		}

		// Copy cwd
		doffs = get_abs_correct_path(dst, cwd, NULL, dst_len);
		if(doffs < 0) return doffs;

		// Add '/' if not there
		if(doffs >= 1 && dst[doffs-1] != '/')
		{
			if(doffs+1 > dst_len)
			{
				errno = EFAULT;
				return -1;
			}
			dst[doffs++] = '/';
		}
	}

	// Advance through buffer
	int slash_accum = 0;
	int dot_accum = 0;
	while(src[soffs] != '\x00' && doffs < dst_len)
	{
		// Deal to extra slashes
		if(slash_accum > 0)
		{
			if(src[soffs] == '/')
			{
				soffs++;
				continue;
			} else {
				slash_accum = 0;
			}
		}

		// Deal to multiple dots
		if(dot_accum > 0)
		{
			if(src[soffs] == '.')
			{
				dst[doffs++] = src[soffs++];
				dot_accum++;
				continue;

			} else if(src[soffs] == '/') {
				// Delete paths
				if(doffs > 0) doffs--;
				while(dot_accum > 0 && doffs > 0)
				{
					if(dst[doffs] == '/')
						dot_accum--;

					doffs--;
				}
				if(dot_accum <= 0) doffs++;

				// Readd trailing slash
				dst[doffs++] = src[soffs++];
				dot_accum = 0;
				slash_accum = 1;
				continue;

			} else {
				dst[doffs++] = src[soffs++];
				dot_accum = 0;
				continue;
			}
		}

		if(src[soffs] == '/')
		{
			dst[doffs++] = src[soffs++];
			slash_accum = 1;
		} else if(src[soffs] == '.') {
			dst[doffs++] = src[soffs++];
			dot_accum = 1;
		} else {
			dst[doffs++] = src[soffs++];
		}
	}

	// Do we have room for the NUL byte?
	if(doffs >= dst_len)
	{
		errno = EFAULT;
		return -1;
	}

	// Insert NUL and return
	dst[doffs] = '\x00';
	return doffs;
}

int mount_filesystem(const char *address, const char *path)
{
	// Ensure path + address are small enough
	if(strlen(address) > 39 || strlen(path) > 39)
	{
		errno = E2BIG;
		return -1;
	}
	// Find a free slot
	int fsidx;

	for(fsidx = 0; fsidx < DRIVE_MAX; fsidx++)
	{
		if(fs_address[fsidx][0] == 0)
			break;
	}

	if(fsidx == DRIVE_MAX)
	{
		errno = EIO;
		return -1;
	}

	// Set mount point + address
	strncpy(fs_address[fsidx], address, 39);
	fs_address[fsidx][39] = 0;

	strncpy(fs_mount_point[fsidx], path, 39);
	fs_mount_point[fsidx][39] = 0;

	// All done!
	return 0;
}

int mclib_gpu_get_resolution(int *w, int *h)
{
	char addr_tmp[64];
	memcpy(addr_tmp, (uint8_t *)0xBFF00200, 64);
	memcpy((uint8_t *)0xBFF00200, gpu_address, 64);
	*(volatile char *volatile*volatile)0xBFF00280 = "getResolution";
	*(volatile uint8_t *)0xBFF00286 = 0;
	memcpy((uint8_t *)0xBFF00200, addr_tmp, 64);

	if(*(volatile int8_t *)0xBFF00286 < 2)
		return -1;
	if(SYS_ARG_TYP(0) != 6)
		return -1;
	if(SYS_ARG_TYP(0) != 6)
		return -1;

	*w = SYS_ARG_INT(0);
	*h = SYS_ARG_INT(1);

	return 0;
}

int mclib_gpu_set_resolution(int w, int h)
{
	char addr_tmp[64];
	memcpy(addr_tmp, (uint8_t *)0xBFF00200, 64);
	memcpy((uint8_t *)0xBFF00200, gpu_address, 64);
	*(volatile char *volatile*volatile)0xBFF00280 = "setResolution";
	SYS_ARG_INT(0) = w;
	SYS_ARG_TYP(0) = STYP_INT;
	SYS_ARG_INT(1) = h;
	SYS_ARG_TYP(1) = STYP_INT;
	*(volatile uint8_t *)0xBFF00286 = 2;
	memcpy((uint8_t *)0xBFF00200, addr_tmp, 64);

	if(*(volatile int8_t *)0xBFF00286 < 0)
		return -1;

	return 0;
}

void mclib_gpu_fill(int x, int y, int w, int h, const char *cs)
{
	char addr_tmp[64];
	memcpy(addr_tmp, (uint8_t *)0xBFF00200, 64);
	memcpy((uint8_t *)0xBFF00200, gpu_address, 64);
	*(volatile char *volatile*volatile)0xBFF00280 = "fill";
	SYS_ARG_INT(0) = x; SYS_ARG_TYP(0) = STYP_INT;
	SYS_ARG_INT(1) = y; SYS_ARG_TYP(1) = STYP_INT;
	SYS_ARG_INT(2) = w; SYS_ARG_TYP(2) = STYP_INT;
	SYS_ARG_INT(3) = h; SYS_ARG_TYP(3) = STYP_INT;
	SYS_ARG_STR(4) = cs; SYS_ARG_TYP(4) = STYP_STR;
	*(volatile uint8_t *)0xBFF00286 = 5;
	memcpy((uint8_t *)0xBFF00200, addr_tmp, 64);
}

void mclib_gpu_copy(int x, int y, int w, int h, int dx, int dy)
{
	char addr_tmp[64];
	memcpy(addr_tmp, (uint8_t *)0xBFF00200, 64);
	memcpy((uint8_t *)0xBFF00200, gpu_address, 64);
	*(volatile char *volatile*volatile)0xBFF00280 = "copy";
	SYS_ARG_INT(0) = x; SYS_ARG_TYP(0) = STYP_INT;
	SYS_ARG_INT(1) = y; SYS_ARG_TYP(1) = STYP_INT;
	SYS_ARG_INT(2) = w; SYS_ARG_TYP(2) = STYP_INT;
	SYS_ARG_INT(3) = h; SYS_ARG_TYP(3) = STYP_INT;
	SYS_ARG_INT(4) = dx; SYS_ARG_TYP(4) = STYP_INT;
	SYS_ARG_INT(5) = dy; SYS_ARG_TYP(5) = STYP_INT;
	*(volatile uint8_t *)0xBFF00286 = 6;
	memcpy((uint8_t *)0xBFF00200, addr_tmp, 64);
}

void mclib_gpu_set(int x, int y, const char *s)
{
	char addr_tmp[64];
	memcpy(addr_tmp, (uint8_t *)0xBFF00200, 64);
	memcpy((uint8_t *)0xBFF00200, gpu_address, 64);
	*(volatile char *volatile*volatile)0xBFF00280 = "set";
	SYS_ARG_INT(0) = x; SYS_ARG_TYP(0) = STYP_INT;
	SYS_ARG_INT(1) = y; SYS_ARG_TYP(1) = STYP_INT;
	SYS_ARG_STR(2) = s; SYS_ARG_TYP(2) = STYP_STR;
	*(volatile uint8_t *)0xBFF00286 = 3;
	memcpy((uint8_t *)0xBFF00200, addr_tmp, 64);
}

void mclib_gpu_set_pal(int idx, int rgb)
{
	char addr_tmp[64];
	memcpy(addr_tmp, (uint8_t *)0xBFF00200, 64);
	memcpy((uint8_t *)0xBFF00200, gpu_address, 64);
	*(volatile char *volatile*volatile)0xBFF00280 = "setPaletteColor";
	SYS_ARG_INT(0) = idx; SYS_ARG_TYP(0) = STYP_INT;
	SYS_ARG_INT(1) = rgb; SYS_ARG_TYP(1) = STYP_INT;
	*(volatile uint8_t *)0xBFF00286 = 2;
	memcpy((uint8_t *)0xBFF00200, addr_tmp, 64);
}


void mclib_gpu_set_fg(int rgb, int is_pal)
{
	char addr_tmp[64];
	memcpy(addr_tmp, (uint8_t *)0xBFF00200, 64);
	memcpy((uint8_t *)0xBFF00200, gpu_address, 64);
	*(volatile char *volatile*volatile)0xBFF00280 = "setForeground";
	SYS_ARG_INT(0) = rgb; SYS_ARG_TYP(0) = STYP_INT;
	SYS_ARG_INT(1) = is_pal; SYS_ARG_TYP(1) = STYP_BOL;
	*(volatile uint8_t *)0xBFF00286 = 2;
	memcpy((uint8_t *)0xBFF00200, addr_tmp, 64);
}

void mclib_gpu_set_bg(int rgb, int is_pal)
{
	char addr_tmp[64];
	memcpy(addr_tmp, (uint8_t *)0xBFF00200, 64);
	memcpy((uint8_t *)0xBFF00200, gpu_address, 64);
	*(volatile char *volatile*volatile)0xBFF00280 = "setBackground";
	SYS_ARG_INT(0) = rgb; SYS_ARG_TYP(0) = STYP_INT;
	SYS_ARG_INT(1) = is_pal; SYS_ARG_TYP(1) = STYP_BOL;
	*(volatile uint8_t *)0xBFF00286 = 2;
	memcpy((uint8_t *)0xBFF00200, addr_tmp, 64);
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

int open(const char *pathname, int flags, ...)
{
	const char *acc_flags = NULL;

	switch(flags & O_ACCMODE)
	{
		case O_RDONLY:
			// TODO: find out the condition for the w+ mode
			acc_flags = "r";
			break;
		case O_WRONLY:
			if(flags & O_APPEND)
				acc_flags = "a";
			else
				acc_flags = "w";
			break;
		case O_RDWR:
			if(flags & O_APPEND)
				acc_flags = "a+";
			else
				acc_flags = "r+";
			break;

	}

	char addr_tmp[64];
	memcpy(addr_tmp, (uint8_t *)0xBFF00200, 64);

	// Find path
	int retcode = -1;
	char path_tmp[256];
	ssize_t plen = get_abs_correct_path(path_tmp, pathname, "/", 256);
	if(plen < 0)
	{
		//perror("get_abs_correct_path");

	} else {
		const char *outaddr;
		const char *outpath;
		//printf("mount path: \"%s\"\n", path_tmp);
		if(lookup_mount_path(path_tmp, &outaddr, &outpath) < 0)
		{
			//perror("lookup_mount_path");

		} else {
			memcpy((uint8_t *)0xBFF00200, outaddr, 64);
			*(volatile char **)0xBFF00280 = "open";
			SYS_ARG_STR(0) = outpath; SYS_ARG_TYP(0) = STYP_STR;
			SYS_ARG_STR(1) = acc_flags; SYS_ARG_TYP(1) = STYP_STR;
			*(volatile uint8_t *)0xBFF00286 = 2;
			int retcnt = *(volatile int8_t *)0xBFF00286;
			if(retcnt < 1)
			{
				errno = EACCES;
			} else if(SYS_ARG_TYP(0) == 0) {
				// TODO: determine correct error code
				errno = EACCES;
			} else if(fs_fdtyp != 0 && SYS_ARG_TYP(0) != fs_fdtyp) {
				// no really, determine that
				errno = EACCES;

				*(volatile char **)0xBFF00280 = "close";
				// abuse the fact that our FD is lying in there
				*(volatile uint8_t *)0xBFF00286 = 1;
			} else if(fs_open_index[SYS_ARG_INT(0) % FILE_MAX] != 0) {
				// pretty sure there's one for running out of fdescs, too
				printf("fd slot collision -- FIXME!\n");
				errno = EACCES;

				*(volatile char **)0xBFF00280 = "close";
				// abuse the fact that our FD is lying in there
				*(volatile uint8_t *)0xBFF00286 = 1;
			} else {
				// we can return a valid file handle!
				fs_fdtyp = SYS_ARG_TYP(0);
				retcode = SYS_ARG_INT(0);
				fs_open_index[retcode % FILE_MAX] = retcode;
				strncpy(fs_open_address[retcode % FILE_MAX], outaddr, 39);
				fs_open_address[retcode % FILE_MAX][39] = 0;
				retcode += 3;
			}

		}
	}

	memcpy((uint8_t *)0xBFF00200, addr_tmp, 64);

	return retcode;
}

int fstat(int fd, struct stat *buf)
{
	//char sbuf[512]; sprintf(sbuf, "fstat = %i\n", fd); write(1, sbuf, strlen(sbuf));
	memset(buf, 0, sizeof(struct stat));
	return 0;
}

int poll_event(void)
{
	int ev_args = *(volatile int8_t *)0xBFF00287;
	if(ev_args <= 0) return 0;

	char signame[64];
	int buflen = SYS_ARG_INT(0);
	if(buflen > 63) buflen = 63;
	*(volatile char **)0xBFF00288 = signame;
	*(volatile int32_t *)0xBFF0028C = buflen;
	signame[0] = '\x00';
	*(volatile int8_t *)0xBFF00287 = 0;
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
			*(volatile uint32_t *)0xBFF00020 = 1;
			continue;
		}
	} else if(fd < 3) {
		errno = EINVAL;
		return -1;
	}

	// check if we have this fd
	int idx = (fd-3);
	int sidx = idx % FILE_MAX;
	if(fs_open_index[sidx] != idx)
	{
		errno = EINVAL;
		return -1;
	}

	// read
	char addr_tmp[64];
	memcpy(addr_tmp, (uint8_t *)0xBFF00200, 64);
	strncpy((uint8_t *)0xBFF00200, fs_open_address[sidx], 64);
	*(volatile char **)0xBFF00280 = "read";
	SYS_ARG_INT(0) = idx; SYS_ARG_TYP(0) = fs_fdtyp;
	SYS_ARG_INT(1) = amt; SYS_ARG_TYP(1) = STYP_INT;
	*(volatile uint8_t *)0xBFF00286 = 2;

	int retcnt = *(volatile uint8_t *)0xBFF00286;
	if(retcnt < 1)
	{
		memcpy((uint8_t *)0xBFF00200, addr_tmp, 64);
		errno = EPIPE; // not sure what the error *really* is
		return -1;
	}

	if(SYS_ARG_TYP(0) == STYP_NUL)
	{
		memcpy((uint8_t *)0xBFF00200, addr_tmp, 64);
		return 0;
	}
	
	if(SYS_ARG_TYP(0) != STYP_STR)
	{
		memcpy((uint8_t *)0xBFF00200, addr_tmp, 64);
		errno = EPIPE; // not sure what the error *really* is either
		return -1;
	}

	// read string
	int out_len = SYS_ARG_INT(0);
	if(out_len > amt) out_len = amt;
	*(volatile char **)0xBFF00288 = (char *)buf;
	*(volatile int32_t *)0xBFF0028C = out_len;
	*(volatile int8_t *)0xBFF00287 = 0;
	memcpy((uint8_t *)0xBFF00200, addr_tmp, 64);

	return out_len;
}

off_t lseek(int fd, off_t offset, int whence)
{
	if(fd < 3) {
		errno = EINVAL;
		return -1;
	}

	// check if we have this fd
	int idx = (fd-3);
	int sidx = idx % FILE_MAX;
	if(fs_open_index[sidx] != idx)
	{
		errno = EINVAL;
		return -1;
	}

	// seek
	char addr_tmp[64];
	memcpy(addr_tmp, (uint8_t *)0xBFF00200, 64);
	strncpy((uint8_t *)0xBFF00200, fs_open_address[sidx], 64);
	*(volatile char **)0xBFF00280 = "seek";
	SYS_ARG_INT(0) = idx; SYS_ARG_TYP(0) = fs_fdtyp;
	SYS_ARG_STR(1) = (whence == SEEK_SET ? "set" : whence == SEEK_END ? "end" : "cur");
		SYS_ARG_TYP(1) = STYP_STR;
	SYS_ARG_INT(2) = offset; SYS_ARG_TYP(2) = STYP_INT;
	*(volatile uint8_t *)0xBFF00286 = 3;

	int retcnt = *(volatile uint8_t *)0xBFF00286;
	if(retcnt < 1)
	{
		memcpy((uint8_t *)0xBFF00200, addr_tmp, 64);
		errno = EPIPE; // not sure what the error *really* is
		return -1;
	}

	// return offset
	return SYS_ARG_INT(0);
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
		uint32_t wallclock_high = *(volatile uint32_t *)0xBFF00024;
		uint32_t wallclock_low = *(volatile uint32_t *)0xBFF00020;

		// If the upper bits don't match, fetch it again!
		if((wallclock_high<<20) != (wallclock_low&~((1<<20-1))))
		{
			wallclock_high = *(volatile uint32_t *)0xBFF00024;
			wallclock_low = *(volatile uint32_t *)0xBFF00020;
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
	// avoid stdio + erroneous fds
	if(fd < 3) return 0;

	// check if we can close a given thing
	int idx = (fd-3);
	int sidx = idx % FILE_MAX;
	if(fs_open_index[sidx] != idx) return 0; // collision

	// close it
	char addr_tmp[64];
	memcpy(addr_tmp, (uint8_t *)0xBFF00200, 64);
	strncpy((uint8_t *)0xBFF00200, fs_open_address[sidx], 64);
	*(volatile char **)0xBFF00280 = "close";
	SYS_ARG_INT(0) = idx; SYS_ARG_TYP(0) = fs_fdtyp;
	// abuse the fact that our FD is lying in there
	*(volatile uint8_t *)0xBFF00286 = 1;
	memcpy((uint8_t *)0xBFF00200, addr_tmp, 64);

	// free stuff
	fs_open_index[sidx] = 0;
	fs_open_address[sidx][0] = 0;

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
		"lui $gp, %%hi(%0)\n"
		"addiu $gp, $gp, %%lo(%0)\n"
		:
		: "i"(_gp)
		: 
	);

	// mount root
	mount_filesystem((uint8_t *)0xBFF00200, "/");

	char *argv_base[2] = {
		"init",
		NULL
	};

	const char *gpudev = mclib_find_device("gpu");
	if(gpudev == NULL)
	{
		*(volatile uint8_t *)0xBFF00004 = ';';
		*(volatile uint8_t *)0xBFF00004 = '_';
		*(volatile uint8_t *)0xBFF00004 = ';';
		*(volatile uint8_t *)0xBFF00004 = '\n';
		for(;;) {}
	}

	memcpy(gpu_address, (char *)0xBFF00200, 64);

	/*
	int i;
	for(i = 0; gpu_address[i] != '\x00'; i++)
		*(volatile uint8_t *)0xBFF00004 = gpu_address[i];
	*(volatile uint8_t *)0xBFF00004 = '\n';
	*/

	// clear screen
	mclib_gpu_get_resolution(&gpu_w, &gpu_h);
	mclib_gpu_fill(1, 1, gpu_w, gpu_h, " ");
	//sbrk(0);
	volatile int r = main(1, argv_base);

	(void)r;
}

