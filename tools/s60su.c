/* s60su - minimal setuid-root shell launcher for the JioPhone boot ramdisk.
 * Installed in the ramdisk at /sbin/s60su, mode 06755 (setuid root). The
 * initramfs rootfs honors setuid and SELinux is permissive, so exec'ing this
 * from an (unprivileged) adb shell yields a real uid=0 shell.
 *   adb shell /sbin/s60su            -> interactive root shell
 *   adb shell /sbin/s60su -c '<cmd>' -> run <cmd> as root
 */
#define _GNU_SOURCE
#include <unistd.h>

int main(int argc, char **argv) {
    setresgid(0, 0, 0);
    setresuid(0, 0, 0);
    if (argc > 1) {
        argv[0] = "sh";                 /* sh <args...> */
        execv("/system/bin/sh", argv);
        execv("/system/bin/mksh", argv);
        return 127;
    }
    execl("/system/bin/sh", "sh", (char *)0);
    execl("/system/bin/mksh", "sh", (char *)0);
    return 127;
}
