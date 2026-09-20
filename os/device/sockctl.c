/* sockctl - talk to Android daemons over their control sockets.
 *
 * The S100 shell's scripts need two tiny client protocols that no shell
 * tool speaks (toybox nc has no Unix sockets):
 *
 *   sockctl ril <n> [arg]
 *       rild's debug port (/dev/socket/rild-debug, what the AOSP
 *       radiooptions tool uses): a native int with the argument count,
 *       then for each argument a native int length and the bytes.
 *       n = 0 reset, 1 radio off, 5 radio on, 6 setup data call <apn>,
 *       7 deactivate data call <cid>, 8 dial <number>, 9 answer,
 *       10 hang up. rild sends nothing back.
 *
 *   sockctl send <socket> <arg>...
 *       the legacy VPN daemons (mtpd, racoon): each argument as a
 *       big-endian 16-bit length + bytes, terminated by 0xFFFF; the
 *       daemon answers with one status byte (0 = ok) which is printed
 *       as "result=<n>".
 *
 * Built static by scripts/build.sh into out/j2me/bin/sockctl.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/un.h>

static int connect_unix(const char *path) {
    struct sockaddr_un a;
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        return -1;
    }
    memset(&a, 0, sizeof(a));
    a.sun_family = AF_UNIX;
    strncpy(a.sun_path, path, sizeof(a.sun_path) - 1);
    if (connect(fd, (struct sockaddr *) &a, sizeof(a)) != 0) {
        close(fd);
        return -1;
    }
    return fd;
}

static int write_all(int fd, const void *p, size_t n) {
    const char *c = (const char *) p;
    while (n > 0) {
        ssize_t w = write(fd, c, n);
        if (w <= 0) {
            return -1;
        }
        c += w;
        n -= (size_t) w;
    }
    return 0;
}

static int ril(int argc, char **argv) {
    const char *sock = "/dev/socket/rild-debug";
    int fd, i, n;
    if (argc > 0 && strncmp(argv[0], "-s", 2) == 0 && argc > 1) {
        sock = argv[1];
        argv += 2;
        argc -= 2;
    }
    if (argc < 1) {
        fprintf(stderr, "sockctl ril <n> [arg]\n");
        return 2;
    }
    fd = connect_unix(sock);
    if (fd < 0) {
        perror(sock);
        return 1;
    }
    n = argc;
    if (write_all(fd, &n, sizeof(n)) != 0) {
        return 1;
    }
    for (i = 0; i < argc; i++) {
        int len = (int) strlen(argv[i]);
        if (write_all(fd, &len, sizeof(len)) != 0 || write_all(fd, argv[i], (size_t) len) != 0) {
            return 1;
        }
    }
    /* give rild a moment to read before the socket goes away */
    usleep(200000);
    close(fd);
    printf("sent=%d\n", argc);
    return 0;
}

static int send_args(int argc, char **argv) {
    int fd, i;
    unsigned char end[2] = {0xff, 0xff};
    unsigned char result = 0xff;
    if (argc < 1) {
        fprintf(stderr, "sockctl send <socket> <arg>...\n");
        return 2;
    }
    fd = connect_unix(argv[0]);
    if (fd < 0) {
        perror(argv[0]);
        return 1;
    }
    for (i = 1; i < argc; i++) {
        size_t len = strlen(argv[i]);
        unsigned char hdr[2];
        hdr[0] = (unsigned char) (len >> 8);
        hdr[1] = (unsigned char) (len & 0xff);
        if (write_all(fd, hdr, 2) != 0 || write_all(fd, argv[i], len) != 0) {
            return 1;
        }
    }
    if (write_all(fd, end, 2) != 0) {
        return 1;
    }
    if (read(fd, &result, 1) != 1) {
        result = 0xff;
    }
    close(fd);
    printf("result=%d\n", result);
    return result == 0 ? 0 : 1;
}

int main(int argc, char **argv) {
    if (argc >= 2 && strcmp(argv[1], "ril") == 0) {
        return ril(argc - 2, argv + 2);
    }
    if (argc >= 2 && strcmp(argv[1], "send") == 0) {
        return send_args(argc - 2, argv + 2);
    }
    fprintf(stderr, "usage: sockctl ril [-s socket] <n> [arg] | sockctl send <socket> <arg>...\n");
    return 2;
}
