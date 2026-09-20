/* gsu - "group su": re-exec with the Android groups the runtime needs.
 *
 * adbd on the JioPhone runs as `shell` with a capability bounding set of just
 * CAP_SETUID|CAP_SETGID, so /s60su gives uid 0 but *no* CAP_DAC_OVERRIDE:
 * root still cannot open /dev/graphics/fb0 (system:graphics 660) or create
 * /data/j2me (/data is system:system 771). CAP_SETGID is enough to fix that:
 * join the owning groups and plain DAC lets us through.
 *
 *   gsu                 root shell with the extra groups
 *   gsu -c '<cmd>'      run <cmd> (same calling convention as /s60su)
 *   gsu -u <uid> ...    ... as that uid instead of root (e.g. -u 1000 =
 *                       system, the owner of the 644 backlight sysfs node)
 *
 * Built static by scripts/build.sh into out/j2me/bin/gsu; j2me.sh re-execs
 * itself through it when the graphics group is missing.
 */
#define _GNU_SOURCE
#include <grp.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* AID_* values from Android's private/android_filesystem_config.h */
static const gid_t groups[] = {
    0,      /* root */
    1000,   /* system   : /data, /data/misc */
    1003,   /* graphics : /dev/graphics/fb0 */
    1004,   /* input    : /dev/input/event* */
    1005,   /* audio */
    1006,   /* camera */
    1007,   /* log */
    1010,   /* wifi */
    1013,   /* media */
    1015,   /* sdcard_rw */
    1023,   /* media_rw */
    1028,   /* sdcard_r */
    3001,   /* net_bt_admin */
    3002,   /* net_bt */
    3003,   /* inet     : sockets */
    3004,   /* net_raw */
    3005,   /* net_admin */
};

int main(int argc, char **argv) {
    uid_t uid = 0;
    if (argc > 2 && strcmp(argv[1], "-u") == 0) {
        uid = (uid_t)strtoul(argv[2], 0, 10);
        argv += 2;
        argc -= 2;
    }
    setresuid(0, 0, 0);
    setgroups(sizeof groups / sizeof groups[0], groups);
    setresgid(0, 0, 0);
    if (uid != 0 && setresuid(uid, uid, uid) != 0)
        return 126;
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
