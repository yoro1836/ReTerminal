/*
 * xbridge <listen path> <hostsock device>
 * Bridges a guest unix socket (an X11 display socket) to a UML hostdev "sock"
 * device, i.e. to a unix socket on the host. File descriptors a client sends
 * (DRI3 buffers, fences) go out with HOSTSOCK_SEND, which turns hostfd files
 * into the host fds behind them.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>

#define MAX_FDS 8
struct hostsock_send { uint64_t data; uint32_t len; uint32_t nfds; int32_t fds[MAX_FDS]; };
#define HOSTSOCK_SEND _IOW('H', 1, struct hostsock_send)
#define CHUNK (64 << 10)

static const char *dev;

static int write_all(int fd, const char *p, size_t n, int sock)
{
	while (n) {
		ssize_t r = sock ? send(fd, p, n, MSG_NOSIGNAL) : write(fd, p, n);
		if (r < 0 && (errno == EINTR || errno == EAGAIN)) {
			struct pollfd pf = { fd, POLLOUT };
			poll(&pf, 1, 100);
			continue;
		}
		if (r <= 0)
			return -1;
		p += r; n -= r;
	}
	return 0;
}

static void *client(void *arg)
{
	int c = (intptr_t)arg, h = open(dev, O_RDWR | O_NONBLOCK | O_CLOEXEC);
	char *buf = malloc(CHUNK);
	struct pollfd pf[2] = { { c, POLLIN }, { h, POLLIN } };

	if (h < 0) {
		fprintf(stderr, "xbridge: %s: %s\n", dev, strerror(errno));
		goto out;
	}
	for (;;) {
		if (poll(pf, 2, -1) < 0) {
			if (errno == EINTR)
				continue;
			break;
		}
		if (pf[0].revents) {
			union { char b[CMSG_SPACE(sizeof(int) * 2 * MAX_FDS)]; struct cmsghdr a; } ctl;
			struct iovec iov = { buf, CHUNK };
			struct msghdr m = { .msg_iov = &iov, .msg_iovlen = 1, .msg_control = ctl.b, .msg_controllen = sizeof(ctl) };
			ssize_t n = recvmsg(c, &m, MSG_CMSG_CLOEXEC);
			int fds[2 * MAX_FDS], nfds = 0, i;
			size_t done = 0;

			if (n < 0 && errno == EINTR)
				continue;
			if (n <= 0)
				break;
			for (struct cmsghdr *cm = CMSG_FIRSTHDR(&m); cm; cm = CMSG_NXTHDR(&m, cm))
				if (cm->cmsg_level == SOL_SOCKET && cm->cmsg_type == SCM_RIGHTS) {
					nfds = (cm->cmsg_len - CMSG_LEN(0)) / sizeof(int);
					memcpy(fds, CMSG_DATA(cm), nfds * sizeof(int));
				}
			if (nfds) {
				struct hostsock_send s = { (uintptr_t)buf, n, nfds > MAX_FDS ? MAX_FDS : nfds };
				long r;

				memcpy(s.fds, fds, s.nfds * sizeof(int));
				r = ioctl(h, HOSTSOCK_SEND, &s);
				if (r < 0)
					fprintf(stderr, "xbridge: cannot pass %d fd(s) to the host: %s\n", nfds, strerror(errno));
				else
					done = r;
				for (i = 0; i < nfds; i++)
					close(fds[i]);
			}
			if (write_all(h, buf + done, n - done, 0))
				break;
		}
		if (pf[1].revents) {
			ssize_t n = read(h, buf, CHUNK);

			if (n < 0 && (errno == EINTR || errno == EAGAIN))
				continue;
			if (n <= 0 || write_all(c, buf, n, 1))
				break;
		}
	}
out:
	if (h >= 0)
		close(h);
	close(c);
	free(buf);
	return NULL;
}

int main(int argc, char **argv)
{
	struct sockaddr_un sun = { .sun_family = AF_UNIX };
	char dir[108], *sl;
	int s;

	if (argc != 3) {
		fprintf(stderr, "usage: xbridge <listen path> <hostsock device>\n");
		return 2;
	}
	dev = argv[2];
	signal(SIGPIPE, SIG_IGN);
	strncpy(sun.sun_path, argv[1], sizeof(sun.sun_path) - 1);
	strcpy(dir, sun.sun_path);
	if ((sl = strrchr(dir, '/')) && sl != dir) {
		*sl = 0;
		mkdir(dir, 01777);
		chmod(dir, 01777);
	}
	unlink(sun.sun_path);
	s = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
	if (s < 0 || bind(s, (struct sockaddr *)&sun, sizeof(sun)) || listen(s, 16)) {
		perror("xbridge: listen");
		return 1;
	}
	chmod(sun.sun_path, 0777);
	for (;;) {
		int c = accept4(s, NULL, NULL, SOCK_CLOEXEC);
		pthread_t t;
		pthread_attr_t a;

		if (c < 0)
			continue;
		pthread_attr_init(&a);
		pthread_attr_setdetachstate(&a, PTHREAD_CREATE_DETACHED);
		if (pthread_create(&t, &a, client, (void *)(intptr_t)c))
			close(c);
	}
}
