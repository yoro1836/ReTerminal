#include <errno.h>
#include <poll.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

static int write_all(int fd, const uint8_t *buffer, size_t length) {
    while (length > 0) {
        ssize_t written = write(fd, buffer, length);
        if (written < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        buffer += written;
        length -= (size_t) written;
    }
    return 0;
}

static volatile sig_atomic_t window_size_changed = 1;

static void handle_window_size_change(int signal_number) {
    (void)signal_number;
    window_size_changed = 1;
}

static int send_window_size(int fd) {
    struct winsize size;
    if (ioctl(STDIN_FILENO, TIOCGWINSZ, &size) < 0) return -1;

    char message[64];
    int length = snprintf(message, sizeof(message), "%u %u %u %u\n",
                          size.ws_row, size.ws_col, size.ws_xpixel, size.ws_ypixel);
    if (length <= 0 || (size_t)length >= sizeof(message)) {
        errno = EOVERFLOW;
        return -1;
    }
    return write_all(fd, (const uint8_t *)message, (size_t)length);
}

static int connect_abstract(const char *name) {
    if (strlen(name) + 1 >= sizeof(((struct sockaddr_un *)0)->sun_path)) {
        errno = ENAMETOOLONG;
        return -1;
    }

    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;

    struct sockaddr_un address;
    memset(&address, 0, sizeof(address));
    address.sun_family = AF_UNIX;
    memcpy(address.sun_path + 1, name, strlen(name));
    socklen_t size = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + strlen(name));

    for (int attempt = 0; attempt < 600; ++attempt) {
        if (connect(fd, (struct sockaddr *)&address, size) == 0) return fd;
        if (errno != ENOENT && errno != ECONNREFUSED) break;
        struct timespec delay = {.tv_sec = 0, .tv_nsec = 100000000};
        nanosleep(&delay, NULL);
    }

    close(fd);
    return -1;
}

int main(int argc, char **argv) {
    if (argc != 3) {
        dprintf(STDERR_FILENO, "usage: %s DATA_SOCKET WINDOW_SIZE_SOCKET\r\n", argv[0]);
        return 2;
    }

    signal(SIGPIPE, SIG_IGN);
    struct sigaction window_size_action;
    memset(&window_size_action, 0, sizeof(window_size_action));
    window_size_action.sa_handler = handle_window_size_change;
    sigemptyset(&window_size_action.sa_mask);
    if (sigaction(SIGWINCH, &window_size_action, NULL) < 0) {
        dprintf(STDERR_FILENO, "ReTerminal AVF bridge: SIGWINCH setup failed: %s\r\n",
                strerror(errno));
        return 1;
    }

    struct termios terminal;
    if (tcgetattr(STDIN_FILENO, &terminal) == 0) {
        cfmakeraw(&terminal);
        if (tcsetattr(STDIN_FILENO, TCSANOW, &terminal) < 0) {
            dprintf(STDERR_FILENO, "ReTerminal AVF bridge: raw terminal setup failed: %s\r\n",
                    strerror(errno));
            return 1;
        }
    }

    int socket_fd = connect_abstract(argv[1]);
    if (socket_fd < 0) {
        dprintf(STDERR_FILENO, "ReTerminal AVF bridge: data connect failed: %s\r\n",
                strerror(errno));
        return 1;
    }
    int window_size_fd = connect_abstract(argv[2]);
    if (window_size_fd < 0) {
        dprintf(STDERR_FILENO, "ReTerminal AVF bridge: window size connect failed: %s\r\n",
                strerror(errno));
        close(socket_fd);
        return 1;
    }

    struct pollfd fds[2] = {
        {.fd = STDIN_FILENO, .events = POLLIN},
        {.fd = socket_fd, .events = POLLIN},
    };
    uint8_t buffer[16384];

    while (1) {
        if (window_size_changed) {
            window_size_changed = 0;
            if (send_window_size(window_size_fd) < 0) break;
        }

        int poll_result = poll(fds, 2, -1);
        if (poll_result < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (fds[0].revents & POLLIN) {
            ssize_t count = read(STDIN_FILENO, buffer, sizeof(buffer));
            if (count <= 0 || write_all(socket_fd, buffer, (size_t)count) < 0) break;
        }
        if (fds[1].revents & POLLIN) {
            ssize_t count = read(socket_fd, buffer, sizeof(buffer));
            if (count <= 0 || write_all(STDOUT_FILENO, buffer, (size_t)count) < 0) break;
        }
        if (fds[0].revents & (POLLERR | POLLHUP | POLLNVAL)) break;
        if (fds[1].revents & (POLLERR | POLLHUP | POLLNVAL)) break;
    }

    close(window_size_fd);
    close(socket_fd);
    return 0;
}
