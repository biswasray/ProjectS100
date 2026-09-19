/* keyprobe - dump evdev keypad events on the phone (static ARM binary).
 *
 * Lists every /dev/input/eventN with EV_KEY capability, then prints each
 * key press/release as "<device> code=<n> value=<0|1|2>". Use it once per
 * device to fill in device/keymap.txt (see os/README.md); Ctrl-C or the
 * END/POWER key held for ~3 s (code 116) exits.
 *
 *   arm-linux-gnueabi-gcc -static -O2 -o keyprobe keyprobe.c
 *   adb push keyprobe /data/local/tmp/ && adb shell /data/local/tmp/keyprobe
 */
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>
#include <linux/input.h>

#define MAXDEV 16
#define BITS_PER_LONG (sizeof(long) * 8)
#define NBITS(x) ((((x) - 1) / BITS_PER_LONG) + 1)
#define TEST_BIT(bit, arr) ((arr[(bit) / BITS_PER_LONG] >> ((bit) % BITS_PER_LONG)) & 1)

int main(int argc, char **argv) {
    struct pollfd pfd[MAXDEV];
    char names[MAXDEV][80];
    char paths[MAXDEV][32];
    int n = 0;
    DIR *d;
    struct dirent *de;
    time_t powerDown = 0;

    d = opendir("/dev/input");
    if (d == NULL) {
        perror("/dev/input");
        return 1;
    }
    while ((de = readdir(d)) != NULL && n < MAXDEV) {
        unsigned long evbits[NBITS(EV_MAX)];
        int fd;
        if (strncmp(de->d_name, "event", 5) != 0) continue;
        snprintf(paths[n], sizeof(paths[n]), "/dev/input/%s", de->d_name);
        fd = open(paths[n], O_RDONLY | O_NONBLOCK);
        if (fd < 0) { printf("%-20s open failed: %s\n", paths[n], strerror(errno)); continue; }
        memset(evbits, 0, sizeof(evbits));
        ioctl(fd, EVIOCGBIT(0, sizeof(evbits)), evbits);
        names[n][0] = '\0';
        ioctl(fd, EVIOCGNAME(sizeof(names[n]) - 1), names[n]);
        printf("%-20s %-32s %s\n", paths[n], names[n],
               TEST_BIT(EV_KEY, evbits) ? "EV_KEY" : "(no keys, ignored)");
        if (!TEST_BIT(EV_KEY, evbits)) { close(fd); continue; }
        pfd[n].fd = fd;
        pfd[n].events = POLLIN;
        n++;
    }
    closedir(d);
    if (n == 0) { fprintf(stderr, "no key devices\n"); return 1; }

    printf("\nPress keys (hold END/POWER 3 s to quit)...\n");
    for (;;) {
        int i;
        if (poll(pfd, n, 500) <= 0) {
            if (powerDown && time(NULL) - powerDown >= 3) break;
            continue;
        }
        for (i = 0; i < n; i++) {
            struct input_event ie;
            if (!(pfd[i].revents & POLLIN)) continue;
            while (read(pfd[i].fd, &ie, sizeof(ie)) == (int)sizeof(ie)) {
                if (ie.type != EV_KEY) continue;
                printf("%-20s code=%-4u value=%d%s\n", paths[i], ie.code, ie.value,
                       ie.value == 2 ? " (repeat)" : "");
                fflush(stdout);
                if (ie.code == KEY_POWER || ie.code == KEY_END) {
                    powerDown = ie.value ? time(NULL) : 0;
                }
            }
        }
    }
    return 0;
}
