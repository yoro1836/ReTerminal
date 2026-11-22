/*
 * libxshmfence.so.1 for a UML guest talking to an X server on the host.
 * The fence is 4 bytes the client and the server both map. Here they are a
 * host memfd from /dev/hostshm, so the host X server can map and trigger it;
 * its futex wake cannot reach a guest, so await polls. Without /dev/hostshm
 * (a local X server) a plain memfd is used and everything still works.
 */
#define _GNU_SOURCE
#include <fcntl.h>
#include <stdint.h>
#include <stdlib.h>
#include <sched.h>
#include <time.h>
#include <unistd.h>
#include <sys/mman.h>

struct xshmfence { int32_t v; };

int xshmfence_alloc_shm(void)
{
	int fd = open("/dev/hostshm", O_RDWR | O_CLOEXEC);

	if (fd >= 0)
		return fd;
	fd = memfd_create("xshmfence", MFD_CLOEXEC | MFD_ALLOW_SEALING);
	if (fd >= 0 && ftruncate(fd, sizeof(struct xshmfence))) {
		close(fd);
		return -1;
	}
	return fd;
}

struct xshmfence *xshmfence_map_shm(int fd)
{
	void *p = mmap(NULL, sizeof(struct xshmfence), PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);

	return p == MAP_FAILED ? NULL : p;
}

void xshmfence_unmap_shm(struct xshmfence *f)
{
	munmap(f, sizeof(*f));
}

int xshmfence_trigger(struct xshmfence *f)
{
	__atomic_store_n(&f->v, 1, __ATOMIC_SEQ_CST);
	return 0;
}

int xshmfence_await(struct xshmfence *f)
{
	struct timespec ts = { 0, 50000 };
	int spins = 0;

	while (__atomic_load_n(&f->v, __ATOMIC_SEQ_CST) != 1) {
		if (++spins > 200)
			nanosleep(&ts, NULL);
		else
			sched_yield();
	}
	return 0;
}

int xshmfence_query(struct xshmfence *f)
{
	return __atomic_load_n(&f->v, __ATOMIC_SEQ_CST) == 1;
}

void xshmfence_reset(struct xshmfence *f)
{
	__atomic_store_n(&f->v, 0, __ATOMIC_SEQ_CST);
}
