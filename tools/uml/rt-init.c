// SPDX-License-Identifier: GPL-2.0
/*
 * /init of ReTerminal's initramfs (libumlinitrd.so, passed as initrd=).
 *
 * The initramfs holds only this program and lib/modules/<release>. It mounts
 * the Debian disk, bind-mounts the modules read-only over the disk's
 * /usr/lib/modules/<release>, moves /dev onto the new root and hands over to
 * /sbin/init. The initramfs is not emptied, so the bind keeps its files.
 *
 * Freestanding (no libc): built with clang --target=aarch64-linux-gnu
 * -nostdlib -static, so it runs on any arm64 kernel regardless of the
 * guest's libc.
 */
typedef unsigned long u64;

#define __NR_mkdirat	34
#define __NR_mount	40
#define __NR_chdir	49
#define __NR_chroot	51
#define __NR_openat	56
#define __NR_close	57
#define __NR_getdents64	61
#define __NR_write	64
#define __NR_exit	93
#define __NR_nanosleep	101
#define __NR_execve	221

#define AT_FDCWD	(-100)
#define O_DIRECTORY	0x4000
#define MS_RDONLY	1
#define MS_REMOUNT	32
#define MS_BIND		4096
#define MS_MOVE		8192

static long sc(long n, long a, long b, long c, long d, long e)
{
	register long x8 __asm__("x8") = n;
	register long x0 __asm__("x0") = a;
	register long x1 __asm__("x1") = b;
	register long x2 __asm__("x2") = c;
	register long x3 __asm__("x3") = d;
	register long x4 __asm__("x4") = e;
	__asm__ volatile("svc #0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4) : "memory");
	return x0;
}

static unsigned long slen(const char *s) { unsigned long n = 0; while (s[n]) n++; return n; }
static void say(const char *s) { sc(__NR_write, 2, (long)s, slen(s), 0, 0); }

static long mnt(const char *src, const char *dst, const char *type, u64 flags, const char *data)
{
	return sc(__NR_mount, (long)src, (long)dst, (long)type, (long)flags, (long)data);
}

static void mkd(const char *p) { sc(__NR_mkdirat, AT_FDCWD, (long)p, 0755, 0, 0); }

static void fail(const char *what)
{
	struct { long s, ns; } t = { 3600, 0 };
	say("rt-init: ");
	say(what);
	say(" failed; cannot boot\n");
	for (;;)
		sc(__NR_nanosleep, (long)&t, 0, 0, 0, 0);
}

/* The one directory under /lib/modules: the release this initramfs was built for. */
static int release(char *out, unsigned long cap)
{
	char buf[1024];
	long fd = sc(__NR_openat, AT_FDCWD, (long)"/lib/modules", O_DIRECTORY, 0, 0);
	long n, i;

	if (fd < 0)
		return -1;
	n = sc(__NR_getdents64, fd, (long)buf, sizeof(buf), 0, 0);
	sc(__NR_close, fd, 0, 0, 0, 0);
	for (i = 0; i < n;) {
		unsigned short reclen = *(unsigned short *)(buf + i + 16);
		const char *name = buf + i + 19;

		if (name[0] != '.') {
			unsigned long l = slen(name);

			if (l + 1 > cap)
				return -1;
			for (unsigned long k = 0; k <= l; k++)
				out[k] = name[k];
			return 0;
		}
		i += reclen;
	}
	return -1;
}

static void cat(char *d, const char *a, const char *b)
{
	unsigned long i = 0, j = 0;

	while (a[i]) { d[i] = a[i]; i++; }
	while (b[j]) d[i++] = b[j++];
	d[i] = 0;
}

/* sp[0] = argc, then argv[], NULL, envp[], NULL: passed on to /sbin/init. */
long cmain(long *sp)
{
	static char rel[256], src[512], dst[512];
	char **argv = (char **)(sp + 1);
	char **envp = argv + sp[0] + 1;

	mkd("/dev");
	mkd("/root");
	mnt("devtmpfs", "/dev", "devtmpfs", 0, 0);

	if (mnt("/dev/ubda", "/root", "ext4", 0, 0) < 0)
		fail("mounting /dev/ubda");

	if (release(rel, sizeof(rel)) == 0) {
		cat(src, "/lib/modules/", rel);
		mkd("/root/usr/lib/modules");
		cat(dst, "/root/usr/lib/modules/", rel);
		mkd(dst);
		if (mnt(src, dst, 0, MS_BIND, 0) == 0)
			mnt(0, dst, 0, MS_BIND | MS_REMOUNT | MS_RDONLY, 0);
		else
			say("rt-init: could not bind kernel modules\n");
	}

	if (mnt("/dev", "/root/dev", 0, MS_MOVE, 0) < 0)
		say("rt-init: could not move /dev\n");
	if (sc(__NR_chdir, (long)"/root", 0, 0, 0, 0) < 0 ||
	    mnt(".", "/", 0, MS_MOVE, 0) < 0 ||
	    sc(__NR_chroot, (long)".", 0, 0, 0, 0) < 0 ||
	    sc(__NR_chdir, (long)"/", 0, 0, 0, 0) < 0)
		fail("switching to /dev/ubda");

	argv[0] = "/sbin/init";
	sc(__NR_execve, (long)argv[0], (long)argv, (long)envp, 0, 0);
	fail("exec /sbin/init");
	return 0;
}

__asm__(".globl _start\n_start:\n mov x0, sp\n bl cmain\n mov x8, #93\n svc #0\n");
