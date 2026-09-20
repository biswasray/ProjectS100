/*
 * S100 shell - time and date formatting.
 *
 * The process time zone comes from $TZ (j2me.sh sets it, see tz.txt);
 * Settings > Date and time can override it for the shell with a
 * "GMT+5:30" style id, which CLDC's TimeZone understands without any
 * zoneinfo files.
 */

package com.sun.midp.appmanager;

import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

final class Clock {
    static final String[] DAYS = {
        "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"
    };
    static final String[] DAYS_SHORT = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
    static final String[] MONTHS = {
        "January", "February", "March", "April", "May", "June", "July",
        "August", "September", "October", "November", "December"
    };
    static final String[] MONTHS_SHORT = {
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    };

    private static TimeZone zone;
    private static String zoneId;

    private Clock() { }

    static TimeZone zone() {
        String id = Prefs.get(Prefs.TIMEZONE, "");
        if (zone == null || !id.equals(zoneId)) {
            zoneId = id;
            if (id.length() == 0) {
                zone = TimeZone.getDefault();
            } else {
                TimeZone z = TimeZone.getTimeZone(id);
                zone = (z == null) ? TimeZone.getDefault() : z;
            }
        }
        return zone;
    }

    static Calendar now() {
        Calendar c = Calendar.getInstance(zone());
        c.setTime(new Date());
        return c;
    }

    static Calendar at(long millis) {
        Calendar c = Calendar.getInstance(zone());
        c.setTime(new Date(millis));
        return c;
    }

    static boolean is24h() {
        return Prefs.getBool(Prefs.CLOCK_24H, true);
    }

    /** "13:05" or "1:05" (12h; am/pm from ampm()). */
    static String time() {
        return time(now());
    }

    static String time(Calendar c) {
        int h = c.get(Calendar.HOUR_OF_DAY);
        int m = c.get(Calendar.MINUTE);
        if (!is24h()) {
            h = h % 12;
            if (h == 0) {
                h = 12;
            }
            return h + ":" + Theme.two(m);
        }
        return Theme.two(h) + ":" + Theme.two(m);
    }

    static String ampm(Calendar c) {
        if (is24h()) {
            return "";
        }
        return c.get(Calendar.HOUR_OF_DAY) < 12 ? "am" : "pm";
    }

    /** "Sunday, 20 September 2026" */
    static String longDate(Calendar c) {
        return DAYS[c.get(Calendar.DAY_OF_WEEK) - 1] + ", "
            + c.get(Calendar.DAY_OF_MONTH) + " "
            + MONTHS[c.get(Calendar.MONTH)] + " " + c.get(Calendar.YEAR);
    }

    /** "Sun 20 Sep" */
    static String shortDate(Calendar c) {
        return DAYS_SHORT[c.get(Calendar.DAY_OF_WEEK) - 1] + " "
            + c.get(Calendar.DAY_OF_MONTH) + " " + MONTHS_SHORT[c.get(Calendar.MONTH)];
    }

    /** "20.09.2026 13:05" for logs. */
    static String stamp(long millis) {
        Calendar c = at(millis);
        return Theme.two(c.get(Calendar.DAY_OF_MONTH)) + "."
            + Theme.two(c.get(Calendar.MONTH) + 1) + "." + c.get(Calendar.YEAR)
            + " " + time(c);
    }

    /** Time zone ids offered in Settings. */
    static final String[] ZONE_IDS = {
        "", "GMT", "GMT+1:00", "GMT+2:00", "GMT+3:00", "GMT+3:30", "GMT+4:00",
        "GMT+4:30", "GMT+5:00", "GMT+5:30", "GMT+5:45", "GMT+6:00", "GMT+6:30",
        "GMT+7:00", "GMT+8:00", "GMT+9:00", "GMT+9:30", "GMT+10:00", "GMT+11:00",
        "GMT+12:00", "GMT-1:00", "GMT-2:00", "GMT-3:00", "GMT-4:00", "GMT-5:00",
        "GMT-6:00", "GMT-7:00", "GMT-8:00", "GMT-9:00", "GMT-10:00"
    };

    static String zoneLabel(String id) {
        return id.length() == 0 ? "Phone default" : id;
    }
}
