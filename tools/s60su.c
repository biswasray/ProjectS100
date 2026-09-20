/* s60su - minimal setuid-root shell launcher for the JioPhone boot ramdisk.
 * Installed in the ramdisk at /sbin/s60su, mode 06755 (setuid root). The
 * initramfs rootfs honors setuid and SELinux is permissive, so exec'ing this
 * from an (unprivileged) adb shell yields a real uid=0 shell.
 *   adb shell /sbin/s60su            -> interactive root shell
 *   adb shell /sbin/s60su -c '<cmd>' -> run <cmd> as root
 *
 * adbd keeps only CAP_SETUID|CAP_SETGID in its bounding set, so this uid-0
 * shell has no CAP_DAC_OVERRIDE and would still be refused by anything not
 * owned by root (/data is system:system 771, /dev/graphics/fb0 is
 * system:graphics 660). Joining the owning groups is the workaround; see
 * os/device/gsu.c for the same trick applied without reflashing the boot.
 */
#define _GNU_SOURCE
#include <grp.h>
#include <unistd.h>

static const gid_t groups[] = {
    0, 1000 /* system */, 1003 /* graphics */, 1004 /* input */, 1005 /* audio */,
    1006 /* camera */, 1007 /* log */, 1010 /* wifi */, 1013 /* media */,
    1015 /* sdcard_rw */, 1023 /* media_rw */, 1028 /* sdcard_r */,
    3001 /* net_bt_admin */, 3002 /* net_bt */, 3003 /* inet */, 3004 /* net_raw */,
    3005 /* net_admin */,
};

int main(int argc, char **argv) {
    setresuid(0, 0, 0);
    setgroups(sizeof groups / sizeof groups[0], groups);
    setresgid(0, 0, 0);
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
