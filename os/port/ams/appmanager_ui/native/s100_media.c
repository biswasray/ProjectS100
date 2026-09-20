/*
 * s100_media.c - the native side of the S100 shell's music and video
 * players (Sys.nMedia*, used by MediaPlayer.java).
 *
 * There is no JSR-135 in this build and no media framework reachable
 * from a static glibc binary (KaiOS's mediaserver/OMX live behind
 * binder), so playback is done here in a helper thread:
 *
 *   sources   WAV (PCM 8/16-bit), MP3 (minimp3, public domain, bundled as
 *             minimp3.h) and the Motion-JPEG AVI clips the camera records
 *             (JPEG frames decoded with the IJG library the MIDP build
 *             links, plus their PCM sound track). Anything else - 3GP/MP4
 *             (H.264/AAC), AMR, MIDI, OGG - is reported as unsupported.
 *   output    every source is converted to 48 kHz stereo S16 with a linear
 *             resampler and a software volume and written straight to the
 *             ALSA front end MultiMedia1 (/dev/snd/pcmC0D0p) with the same
 *             ioctls tinyalsa uses; s100_media.sh sets the codec routing
 *             (speaker/headphones) with tinymix before playback starts.
 *   clock     the audio stream is the clock: position = frames written
 *             minus what the driver still holds. Video frames are decoded
 *             when the clock reaches their time (late frames are skipped);
 *             silent clips run on the wall clock.
 *
 * The Java side polls: nMediaState/nMediaPos every few hundred ms, and
 * nMediaFrame (which copies the last decoded frame under a mutex) from
 * the video screen's ticker. Nothing here calls into the VM from the
 * player thread.
 *
 * -DS100_HOST_TEST builds a command line tool that parses a file, decodes
 * it and writes raw 48 kHz stereo PCM (no ALSA on the host): see
 * os/scripts/check_native.sh.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <stdint.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <pthread.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/time.h>

#ifndef S100_HOST_TEST
#include <kni.h>
#include <sni.h>
#if ENABLE_JPEG
#include <jpeglib.h>
#include <jpegdecoder.h>
#endif
#endif

#ifndef S100_NO_ALSA
#include <sound/asound.h>
#endif

#define MINIMP3_IMPLEMENTATION
#define MINIMP3_NO_SIMD
#include "minimp3.h"

#define OUT_RATE 48000
#define OUT_CH 2
#define CHUNK_FRAMES 1024
#define PCM_DEV "/dev/snd/pcmC0D0p"

enum { T_NONE = 0, T_WAV = 1, T_MP3 = 2, T_AVI = 3 };
enum { ST_STOPPED = 0, ST_PLAYING = 1, ST_PAUSED = 2, ST_ENDED = 3, ST_ERROR = -1 };

static long ms_now(void) {
    struct timeval tv;
    gettimeofday(&tv, NULL);
    return (long) (tv.tv_sec * 1000L + tv.tv_usec / 1000);
}

static unsigned rd16(const unsigned char *p) { return p[0] | (p[1] << 8); }
static unsigned long rd32(const unsigned char *p) {
    return p[0] | (p[1] << 8) | (p[2] << 16) | ((unsigned long) p[3] << 24);
}
static unsigned long rd32be(const unsigned char *p) {
    return ((unsigned long) p[0] << 24) | (p[1] << 16) | (p[2] << 8) | p[3];
}

/* ====================================================================== */
/*                               ALSA output                              */
/* ====================================================================== */

typedef struct {
    int fd;
    unsigned period, periods;
    long written;                 /* frames since the last (re)start */
} pcm_out;

#ifndef S100_NO_ALSA

static struct snd_mask *hw_mask(struct snd_pcm_hw_params *p, int n) {
    return &p->masks[n - SNDRV_PCM_HW_PARAM_FIRST_MASK];
}

static struct snd_interval *hw_interval(struct snd_pcm_hw_params *p, int n) {
    return &p->intervals[n - SNDRV_PCM_HW_PARAM_FIRST_INTERVAL];
}

static void hw_init(struct snd_pcm_hw_params *p) {
    int n;
    memset(p, 0, sizeof(*p));
    for (n = SNDRV_PCM_HW_PARAM_FIRST_MASK; n <= SNDRV_PCM_HW_PARAM_LAST_MASK; n++) {
        struct snd_mask *m = hw_mask(p, n);
        m->bits[0] = ~0U;
        m->bits[1] = ~0U;
    }
    for (n = SNDRV_PCM_HW_PARAM_FIRST_INTERVAL; n <= SNDRV_PCM_HW_PARAM_LAST_INTERVAL; n++) {
        struct snd_interval *i = hw_interval(p, n);
        i->min = 0;
        i->max = ~0U;
    }
    p->rmask = ~0U;
    p->cmask = 0;
    p->info = ~0U;
}

static void hw_set_mask(struct snd_pcm_hw_params *p, int n, unsigned bit) {
    struct snd_mask *m = hw_mask(p, n);
    m->bits[0] = 0;
    m->bits[1] = 0;
    m->bits[bit >> 5] |= 1U << (bit & 31);
}

static void hw_set_int(struct snd_pcm_hw_params *p, int n, unsigned v) {
    struct snd_interval *i = hw_interval(p, n);
    i->min = v;
    i->max = v;
    i->integer = 1;
}

static void hw_set_min(struct snd_pcm_hw_params *p, int n, unsigned v) {
    hw_interval(p, n)->min = v;
}

static int pcm_open_out(pcm_out *o) {
    struct snd_pcm_hw_params hw;
    struct snd_pcm_sw_params sw;
    unsigned long boundary;
    memset(o, 0, sizeof(*o));
    o->fd = open(PCM_DEV, O_RDWR);
    if (o->fd < 0) {
        return -1;
    }
    hw_init(&hw);
    hw_set_mask(&hw, SNDRV_PCM_HW_PARAM_ACCESS, SNDRV_PCM_ACCESS_RW_INTERLEAVED);
    hw_set_mask(&hw, SNDRV_PCM_HW_PARAM_FORMAT, SNDRV_PCM_FORMAT_S16_LE);
    hw_set_mask(&hw, SNDRV_PCM_HW_PARAM_SUBFORMAT, SNDRV_PCM_SUBFORMAT_STD);
    hw_set_int(&hw, SNDRV_PCM_HW_PARAM_SAMPLE_BITS, 16);
    hw_set_int(&hw, SNDRV_PCM_HW_PARAM_FRAME_BITS, 16 * OUT_CH);
    hw_set_int(&hw, SNDRV_PCM_HW_PARAM_CHANNELS, OUT_CH);
    hw_set_int(&hw, SNDRV_PCM_HW_PARAM_RATE, OUT_RATE);
    hw_set_min(&hw, SNDRV_PCM_HW_PARAM_PERIOD_SIZE, CHUNK_FRAMES);
    hw_set_min(&hw, SNDRV_PCM_HW_PARAM_PERIODS, 4);
    if (ioctl(o->fd, SNDRV_PCM_IOCTL_HW_PARAMS, &hw) != 0) {
        close(o->fd);
        o->fd = -1;
        return -2;
    }
    o->period = hw_interval(&hw, SNDRV_PCM_HW_PARAM_PERIOD_SIZE)->min;
    o->periods = hw_interval(&hw, SNDRV_PCM_HW_PARAM_PERIODS)->min;
    if (o->period == 0) o->period = CHUNK_FRAMES;
    if (o->periods == 0) o->periods = 4;
    memset(&sw, 0, sizeof(sw));
    sw.tstamp_mode = SNDRV_PCM_TSTAMP_ENABLE;
    sw.period_step = 1;
    sw.avail_min = o->period;
    sw.start_threshold = o->period * o->periods;
    sw.stop_threshold = o->period * o->periods;
    sw.silence_size = 0;
    sw.silence_threshold = 0;
    boundary = o->period * o->periods;
    while (boundary * 2 <= 0x7fffffffUL - boundary) {
        boundary *= 2;
    }
    sw.boundary = boundary;
    if (ioctl(o->fd, SNDRV_PCM_IOCTL_SW_PARAMS, &sw) != 0) {
        close(o->fd);
        o->fd = -1;
        return -3;
    }
    if (ioctl(o->fd, SNDRV_PCM_IOCTL_PREPARE) != 0) {
        close(o->fd);
        o->fd = -1;
        return -4;
    }
    return 0;
}

static int pcm_write_out(pcm_out *o, const short *buf, unsigned frames) {
    struct snd_xferi x;
    x.result = 0;
    x.buf = (void *) buf;
    x.frames = frames;
    while (ioctl(o->fd, SNDRV_PCM_IOCTL_WRITEI_FRAMES, &x) != 0) {
        if (errno == EPIPE || errno == ESTRPIPE) {
            /* underrun (e.g. after a pause): restart the stream */
            ioctl(o->fd, SNDRV_PCM_IOCTL_PREPARE);
            continue;
        }
        if (errno == EINTR) {
            continue;
        }
        return -1;
    }
    o->written += frames;
    return 0;
}

/** Frames the driver has not played yet. */
static long pcm_delay(pcm_out *o) {
    snd_pcm_sframes_t d = 0;
    if (ioctl(o->fd, SNDRV_PCM_IOCTL_DELAY, &d) != 0 || d < 0) {
        return 0;
    }
    return (long) d;
}

static void pcm_drop(pcm_out *o) {
    ioctl(o->fd, SNDRV_PCM_IOCTL_DROP);
    ioctl(o->fd, SNDRV_PCM_IOCTL_PREPARE);
}

static void pcm_close_out(pcm_out *o) {
    if (o->fd >= 0) {
        ioctl(o->fd, SNDRV_PCM_IOCTL_DROP);
        close(o->fd);
    }
    o->fd = -1;
}

#else /* S100_NO_ALSA: host test writes raw PCM to a file */

static FILE *host_pcm;

static int pcm_open_out(pcm_out *o) {
    memset(o, 0, sizeof(*o));
    o->fd = 1;
    o->period = CHUNK_FRAMES;
    o->periods = 4;
    return host_pcm ? 0 : -1;
}
static int pcm_write_out(pcm_out *o, const short *buf, unsigned frames) {
    fwrite(buf, 4, frames, host_pcm);
    o->written += frames;
    usleep(frames * 1000000UL / OUT_RATE / 20);     /* 20x real time */
    return 0;
}
static long pcm_delay(pcm_out *o) { (void) o; return 0; }
static void pcm_drop(pcm_out *o) { (void) o; }
static void pcm_close_out(pcm_out *o) { o->fd = -1; }

#endif

/* ====================================================================== */
/*                                 sources                                */
/* ====================================================================== */

typedef struct {
    long off, len;
} segment;

typedef struct {
    int type;
    char path[512];
    FILE *f;
    long size;

    /* audio format of the source */
    int has_audio;
    int rate, channels, bits;
    long duration_ms;
    int bitrate_kbps;

    /* WAV: one PCM run; AVI: the '01wb' chunks in order */
    segment *aseg;
    int naseg;
    long audio_bytes;              /* total */
    long apos;                     /* byte position in the audio stream */

    /* video (AVI MJPG) */
    int has_video;
    int w, h;
    double fps;
    segment *vseg;
    int nvseg;
    int video_codec_ok;

    /* MP3 */
    mp3dec_t dec;
    long mp3_first, mp3_pos;
    unsigned char mp3_in[16384];
    int mp3_in_len;
    short mp3_pcm[MINIMP3_MAX_SAMPLES_PER_FRAME];
    int mp3_pcm_n, mp3_pcm_at;     /* stereo frames decoded / consumed */
    long mp3_frames_total;

    char title[128], artist[128], album[128];
} source;

/* --- WAV -------------------------------------------------------------- */

static int open_wav(source *s, const unsigned char *hdr, long hlen) {
    long p = 12;
    int have_fmt = 0;
    if (hlen < 12 || memcmp(hdr + 8, "WAVE", 4) != 0) {
        return -2;
    }
    while (p + 8 <= hlen) {
        unsigned long cl = rd32(hdr + p + 4);
        if (memcmp(hdr + p, "fmt ", 4) == 0 && cl >= 16 && p + 8 + 16 <= hlen) {
            unsigned tag = rd16(hdr + p + 8);
            s->channels = rd16(hdr + p + 10);
            s->rate = (int) rd32(hdr + p + 12);
            s->bits = rd16(hdr + p + 22);
            if ((tag != 1 && tag != 0xfffe) || (s->bits != 8 && s->bits != 16)
                    || s->channels < 1 || s->channels > 2 || s->rate < 4000) {
                return -3;
            }
            have_fmt = 1;
        } else if (memcmp(hdr + p, "data", 4) == 0) {
            long off = p + 8;
            long len = (long) cl;
            if (!have_fmt) {
                return -3;
            }
            if (len <= 0 || off + len > s->size) {
                len = s->size - off;      /* a killed recorder leaves 0 */
            }
            s->aseg = (segment *) malloc(sizeof(segment));
            s->aseg[0].off = off;
            s->aseg[0].len = len;
            s->naseg = 1;
            s->audio_bytes = len;
            s->has_audio = 1;
            s->duration_ms = (long) ((double) len * 1000.0
                                     / (s->rate * s->channels * (s->bits / 8)));
            s->bitrate_kbps = s->rate * s->channels * s->bits / 1000;
            s->type = T_WAV;
            return 0;
        }
        p += 8 + (long) cl + (cl & 1);
    }
    return -2;
}

/* --- AVI -------------------------------------------------------------- */

static int seg_add(segment **v, int *n, int *cap, long off, long len) {
    if (*n == *cap) {
        int nc = *cap ? *cap * 2 : 256;
        segment *nv = (segment *) realloc(*v, nc * sizeof(segment));
        if (nv == NULL) {
            return -1;
        }
        *v = nv;
        *cap = nc;
    }
    (*v)[*n].off = off;
    (*v)[*n].len = len;
    (*n)++;
    return 0;
}

static int open_avi(source *s) {
    unsigned char b[64];
    long pos = 12;
    int vcap = 0, acap = 0;
    int stream_no = 0, vstream = -1, astream = -1;
    unsigned long us_per_frame = 0;
    long movi_off = 0, movi_end = 0;
    memset(b, 0, sizeof(b));
    if (fseek(s->f, 8, SEEK_SET) != 0 || fread(b, 1, 4, s->f) != 4 || memcmp(b, "AVI ", 4) != 0) {
        return -2;
    }
    /* walk the top level: LIST hdrl (parsed recursively), LIST movi, idx1 */
    while (pos + 8 <= s->size) {
        unsigned long cl;
        fseek(s->f, pos, SEEK_SET);
        if (fread(b, 1, 12, s->f) < 8) {
            break;
        }
        cl = rd32(b + 4);
        if (memcmp(b, "LIST", 4) == 0 && memcmp(b + 8, "movi", 4) == 0) {
            movi_off = pos + 12;
            movi_end = pos + 8 + (long) cl;
            if (movi_end > s->size) {
                movi_end = s->size;
            }
        } else if (memcmp(b, "LIST", 4) == 0 && memcmp(b + 8, "hdrl", 4) == 0) {
            long q = pos + 12, qend = pos + 8 + (long) cl;
            while (q + 8 <= qend) {
                unsigned long ql;
                fseek(s->f, q, SEEK_SET);
                if (fread(b, 1, 12, s->f) < 8) {
                    break;
                }
                ql = rd32(b + 4);
                if (memcmp(b, "avih", 4) == 0 && ql >= 40) {
                    unsigned char ah[56];
                    fseek(s->f, q + 8, SEEK_SET);
                    if (fread(ah, 1, 40, s->f) == 40) {
                        us_per_frame = rd32(ah);
                        s->w = (int) rd32(ah + 32);
                        s->h = (int) rd32(ah + 36);
                    }
                } else if (memcmp(b, "LIST", 4) == 0 && memcmp(b + 8, "strl", 4) == 0) {
                    long r = q + 12, rend = q + 8 + (long) ql;
                    int is_vid = 0, is_aud = 0;
                    while (r + 8 <= rend) {
                        unsigned long rl;
                        fseek(s->f, r, SEEK_SET);
                        if (fread(b, 1, 8, s->f) < 8) {
                            break;
                        }
                        rl = rd32(b + 4);
                        if (memcmp(b, "strh", 4) == 0 && rl >= 40) {
                            unsigned char sh[56];
                            fseek(s->f, r + 8, SEEK_SET);
                            if (fread(sh, 1, 40, s->f) == 40) {
                                if (memcmp(sh, "vids", 4) == 0) {
                                    unsigned long scale = rd32(sh + 20), rate = rd32(sh + 24);
                                    is_vid = 1;
                                    vstream = stream_no;
                                    s->video_codec_ok = memcmp(sh + 4, "MJPG", 4) == 0
                                        || memcmp(sh + 4, "mjpg", 4) == 0
                                        || memcmp(sh + 4, "dmb1", 4) == 0
                                        || memcmp(sh + 4, "jpeg", 4) == 0;
                                    if (scale > 0 && rate > 0) {
                                        s->fps = (double) rate / (double) scale;
                                    }
                                } else if (memcmp(sh, "auds", 4) == 0) {
                                    is_aud = 1;
                                    astream = stream_no;
                                }
                            }
                        } else if (memcmp(b, "strf", 4) == 0) {
                            unsigned char sf[40];
                            long n = rl < 40 ? (long) rl : 40;
                            fseek(s->f, r + 8, SEEK_SET);
                            if (fread(sf, 1, n, s->f) == (size_t) n) {
                                if (is_vid && n >= 12) {
                                    if (s->w == 0) s->w = (int) rd32(sf + 4);
                                    if (s->h == 0) s->h = (int) rd32(sf + 8);
                                    if (n >= 20 && !s->video_codec_ok) {
                                        s->video_codec_ok = memcmp(sf + 16, "MJPG", 4) == 0;
                                    }
                                } else if (is_aud && n >= 16) {
                                    unsigned tag = rd16(sf);
                                    s->channels = rd16(sf + 2);
                                    s->rate = (int) rd32(sf + 4);
                                    s->bits = n >= 16 ? rd16(sf + 14) : 16;
                                    if (tag == 1 && (s->bits == 8 || s->bits == 16)
                                            && s->channels >= 1 && s->channels <= 2 && s->rate >= 4000) {
                                        s->has_audio = 1;
                                    }
                                }
                            }
                        }
                        r += 8 + (long) rl + (rl & 1);
                    }
                    stream_no++;
                }
                q += 8 + (long) ql + (ql & 1);
            }
        }
        pos += 8 + (long) cl + (cl & 1);
    }
    if (movi_off == 0) {
        return -2;
    }
    if (s->fps <= 0.0) {
        s->fps = us_per_frame > 0 ? 1000000.0 / (double) us_per_frame : 10.0;
    }
    /* the chunks: NNdc/NNdb video, NNwb audio; nested LIST rec skipped into */
    pos = movi_off;
    while (pos + 8 <= movi_end) {
        unsigned long cl;
        fseek(s->f, pos, SEEK_SET);
        if (fread(b, 1, 8, s->f) < 8) {
            break;
        }
        cl = rd32(b + 4);
        if (memcmp(b, "LIST", 4) == 0) {
            pos += 12;
            continue;
        }
        if (b[0] >= '0' && b[0] <= '9' && b[1] >= '0' && b[1] <= '9') {
            int sn = (b[0] - '0') * 10 + (b[1] - '0');
            if ((b[2] == 'd' && (b[3] == 'c' || b[3] == 'b')) && sn == vstream) {
                if (seg_add(&s->vseg, &s->nvseg, &vcap, pos + 8, (long) cl) != 0) {
                    return -1;
                }
            } else if (b[2] == 'w' && b[3] == 'b' && sn == astream && cl > 0) {
                if (seg_add(&s->aseg, &s->naseg, &acap, pos + 8, (long) cl) != 0) {
                    return -1;
                }
                s->audio_bytes += (long) cl;
            }
        }
        pos += 8 + (long) cl + (cl & 1);
    }
    if (s->naseg == 0) {
        s->has_audio = 0;
    }
    s->has_video = s->nvseg > 0 && s->video_codec_ok;
    if (s->nvseg > 0 && !s->video_codec_ok && !s->has_audio) {
        return -3;                          /* neither stream is playable */
    }
    if (s->has_audio) {
        s->duration_ms = (long) ((double) s->audio_bytes * 1000.0
                                 / (s->rate * s->channels * (s->bits / 8)));
    }
    if (s->nvseg > 0) {
        long vd = (long) (s->nvseg * 1000.0 / s->fps);
        if (vd > s->duration_ms) {
            s->duration_ms = vd;
        }
    }
    s->bitrate_kbps = s->duration_ms > 0 ? (int) (s->size * 8 / s->duration_ms) : 0;
    s->type = T_AVI;
    return 0;
}

/* --- MP3 -------------------------------------------------------------- */

static void id3_text(char *dst, size_t cap, const unsigned char *d, long n) {
    size_t o = 0;
    long i;
    int enc = n > 0 ? d[0] : 0;
    d++;
    n--;
    if (n <= 0) {
        dst[0] = 0;
        return;
    }
    if (enc == 1 || enc == 2) {
        /* UTF-16 (with BOM for 1): keep the Latin range */
        int le = 1;
        i = 0;
        if (enc == 1 && n >= 2 && d[0] == 0xfe && d[1] == 0xff) { le = 0; i = 2; }
        else if (enc == 1 && n >= 2 && d[0] == 0xff && d[1] == 0xfe) { le = 1; i = 2; }
        for (; i + 1 < n && o < cap - 1; i += 2) {
            unsigned c = le ? (d[i] | (d[i + 1] << 8)) : ((d[i] << 8) | d[i + 1]);
            if (c == 0) break;
            dst[o++] = (char) (c < 0x80 ? c : '?');
        }
    } else {
        for (i = 0; i < n && o < cap - 1; i++) {
            if (d[i] == 0) break;
            /* UTF-8 (enc 3) or Latin-1: keep ASCII, blank the rest */
            dst[o++] = (char) (d[i] < 0x80 ? d[i] : '?');
        }
    }
    dst[o] = 0;
    /* trim */
    while (o > 0 && (dst[o - 1] == ' ' || dst[o - 1] == '?')) {
        dst[--o] = 0;
    }
}

static void read_id3(source *s) {
    unsigned char h[10];
    fseek(s->f, 0, SEEK_SET);
    s->mp3_first = 0;
    if (fread(h, 1, 10, s->f) == 10 && memcmp(h, "ID3", 3) == 0) {
        long size = ((h[6] & 0x7f) << 21) | ((h[7] & 0x7f) << 14) | ((h[8] & 0x7f) << 7) | (h[9] & 0x7f);
        int ver = h[3];
        long p = 10, end = 10 + size;
        unsigned char *tag;
        s->mp3_first = end + ((h[5] & 0x10) ? 10 : 0);
        if (size > 0 && size < 4 * 1024 * 1024) {
            tag = (unsigned char *) malloc(size);
            if (tag != NULL && fread(tag, 1, size, s->f) == (size_t) size) {
                if (h[5] & 0x40) {         /* extended header */
                    long eh = ver == 4 ? (((tag[0] & 0x7f) << 21) | ((tag[1] & 0x7f) << 14)
                                          | ((tag[2] & 0x7f) << 7) | (tag[3] & 0x7f))
                                       : (long) rd32be(tag) + 4;
                    p += eh;
                }
                while (p + 10 <= end) {
                    const unsigned char *fr = tag + (p - 10);
                    long fl;
                    if (fr[0] == 0) {
                        break;             /* padding */
                    }
                    if (ver == 4) {
                        fl = ((fr[4] & 0x7f) << 21) | ((fr[5] & 0x7f) << 14) | ((fr[6] & 0x7f) << 7) | (fr[7] & 0x7f);
                    } else {
                        fl = (long) rd32be(fr + 4);
                    }
                    if (fl < 0 || p + 10 + fl > end) {
                        break;
                    }
                    if (memcmp(fr, "TIT2", 4) == 0) id3_text(s->title, sizeof(s->title), fr + 10, fl);
                    else if (memcmp(fr, "TPE1", 4) == 0) id3_text(s->artist, sizeof(s->artist), fr + 10, fl);
                    else if (memcmp(fr, "TALB", 4) == 0) id3_text(s->album, sizeof(s->album), fr + 10, fl);
                    p += 10 + fl;
                }
            }
            free(tag);
        }
    }
    if (s->title[0] == 0 && s->size > 128) {
        unsigned char t[128];
        fseek(s->f, s->size - 128, SEEK_SET);
        if (fread(t, 1, 128, s->f) == 128 && memcmp(t, "TAG", 3) == 0) {
            unsigned char tmp[31];
            memcpy(tmp + 1, t + 3, 30); tmp[0] = 0; id3_text(s->title, sizeof(s->title), tmp, 31);
            memcpy(tmp + 1, t + 33, 30); id3_text(s->artist, sizeof(s->artist), tmp, 31);
            memcpy(tmp + 1, t + 63, 30); id3_text(s->album, sizeof(s->album), tmp, 31);
        }
    }
}

static int mp3_fill(source *s) {
    /* keep the window full from mp3_pos */
    size_t n;
    if (s->mp3_in_len >= (int) sizeof(s->mp3_in)) {
        return s->mp3_in_len;
    }
    fseek(s->f, s->mp3_pos + s->mp3_in_len, SEEK_SET);
    n = fread(s->mp3_in + s->mp3_in_len, 1, sizeof(s->mp3_in) - s->mp3_in_len, s->f);
    s->mp3_in_len += (int) n;
    return s->mp3_in_len;
}

static int open_mp3(source *s) {
    mp3dec_frame_info_t info;
    int samples, tries = 0;
    read_id3(s);
    mp3dec_init(&s->dec);
    s->mp3_pos = s->mp3_first;
    s->mp3_in_len = 0;
    /* first frame: format, and a Xing/Info header for the length */
    for (;;) {
        mp3_fill(s);
        if (s->mp3_in_len <= 0) {
            return -2;
        }
        samples = mp3dec_decode_frame(&s->dec, s->mp3_in, s->mp3_in_len, s->mp3_pcm, &info);
        if (info.frame_bytes == 0) {
            return -2;                    /* no sync anywhere */
        }
        if (samples > 0 || ++tries > 8) {
            break;
        }
        s->mp3_pos += info.frame_bytes;
        s->mp3_in_len = 0;
    }
    if (info.hz <= 0 || info.channels < 1) {
        return -2;
    }
    s->rate = info.hz;
    s->channels = info.channels > 2 ? 2 : info.channels;
    s->bits = 16;
    s->bitrate_kbps = info.bitrate_kbps;
    s->has_audio = 1;
    s->mp3_first = s->mp3_pos;
    {
        /* Xing/Info tag inside the first frame: frame count -> exact length */
        int i;
        const unsigned char *fr = s->mp3_in;
        long frames = 0;
        for (i = 0; i + 16 < info.frame_bytes && i < 200; i++) {
            if ((memcmp(fr + i, "Xing", 4) == 0 || memcmp(fr + i, "Info", 4) == 0)
                    && (rd32be(fr + i + 4) & 1)) {
                frames = (long) rd32be(fr + i + 8);
                break;
            }
        }
        if (frames > 0) {
            s->mp3_frames_total = frames;
            s->duration_ms = (long) ((double) frames * (samples > 0 ? samples : 1152) * 1000.0 / s->rate);
            /* the tag frame itself carries no sound */
            s->mp3_pos += info.frame_bytes;
            s->mp3_in_len = 0;
            s->mp3_first = s->mp3_pos;
        } else if (info.bitrate_kbps > 0) {
            s->duration_ms = (long) ((double) (s->size - s->mp3_first) * 8.0 / info.bitrate_kbps);
        }
    }
    s->mp3_pcm_n = 0;
    s->mp3_pcm_at = 0;
    s->type = T_MP3;
    return 0;
}

/* --- common ----------------------------------------------------------- */

static void src_close(source *s) {
    if (s->f != NULL) {
        fclose(s->f);
    }
    free(s->aseg);
    free(s->vseg);
    memset(s, 0, sizeof(*s));
}

static int src_open(source *s, const char *path) {
    unsigned char hdr[4096];
    long hlen;
    struct stat st;
    const char *ext;
    memset(s, 0, sizeof(*s));
    strncpy(s->path, path, sizeof(s->path) - 1);
    if (stat(path, &st) != 0 || (s->f = fopen(path, "rb")) == NULL) {
        return -1;
    }
    s->size = (long) st.st_size;
    hlen = (long) fread(hdr, 1, sizeof(hdr), s->f);
    if (hlen < 12) {
        src_close(s);
        return -2;
    }
    ext = strrchr(path, '.');
    if (memcmp(hdr, "RIFF", 4) == 0 && memcmp(hdr + 8, "WAVE", 4) == 0) {
        int r = open_wav(s, hdr, hlen);
        if (r != 0) {
            src_close(s);
        }
        return r;
    }
    if (memcmp(hdr, "RIFF", 4) == 0 && memcmp(hdr + 8, "AVI ", 4) == 0) {
        int r = open_avi(s);
        if (r != 0) {
            src_close(s);
        }
        return r;
    }
    if (memcmp(hdr, "ID3", 3) == 0 || (hdr[0] == 0xff && (hdr[1] & 0xe0) == 0xe0)
            || (ext != NULL && (strcasecmp(ext, ".mp3") == 0 || strcasecmp(ext, ".mp2") == 0))) {
        int r = open_mp3(s);
        if (r != 0) {
            src_close(s);
        }
        return r;
    }
    src_close(s);
    return -2;
}

/** Reads n bytes of the audio stream from byte position apos across segments. */
static long src_audio_read(source *s, unsigned char *dst, long n) {
    long done = 0;
    long p = s->apos;
    int i;
    long base = 0;
    for (i = 0; i < s->naseg && done < n; i++) {
        segment *g = &s->aseg[i];
        if (p < base + g->len) {
            long in = p - base;
            long take = g->len - in;
            size_t got;
            if (take > n - done) {
                take = n - done;
            }
            fseek(s->f, g->off + in, SEEK_SET);
            got = fread(dst + done, 1, take, s->f);
            if (got == 0) {
                break;
            }
            done += (long) got;
            p += (long) got;
        }
        base += g->len;
    }
    s->apos = p;
    return done;
}

/**
 * Next source samples as stereo 16-bit frames into dst (max frames).
 * Returns frames, 0 at the end, <0 on error.
 */
static int src_fill(source *s, short *dst, int max) {
    if (s->type == T_MP3) {
        int out = 0;
        while (out < max) {
            if (s->mp3_pcm_at >= s->mp3_pcm_n) {
                mp3dec_frame_info_t info;
                int samples;
                mp3_fill(s);
                if (s->mp3_in_len <= 0) {
                    break;
                }
                samples = mp3dec_decode_frame(&s->dec, s->mp3_in, s->mp3_in_len, s->mp3_pcm, &info);
                if (info.frame_bytes == 0) {
                    break;                /* end of stream / garbage tail */
                }
                s->mp3_pos += info.frame_bytes;
                /* slide the window */
                memmove(s->mp3_in, s->mp3_in + info.frame_bytes, s->mp3_in_len - info.frame_bytes);
                s->mp3_in_len -= info.frame_bytes;
                if (samples <= 0) {
                    continue;
                }
                if (info.channels == 1) {
                    /* expand mono in place, from the end */
                    int i;
                    for (i = samples - 1; i >= 0; i--) {
                        s->mp3_pcm[i * 2] = s->mp3_pcm[i];
                        s->mp3_pcm[i * 2 + 1] = s->mp3_pcm[i];
                    }
                }
                s->mp3_pcm_n = samples;
                s->mp3_pcm_at = 0;
                if (info.hz != s->rate && info.hz > 0) {
                    s->rate = info.hz;    /* rare: the resampler picks it up */
                }
            }
            {
                int take = s->mp3_pcm_n - s->mp3_pcm_at;
                if (take > max - out) {
                    take = max - out;
                }
                memcpy(dst + out * 2, s->mp3_pcm + s->mp3_pcm_at * 2, take * 4);
                s->mp3_pcm_at += take;
                out += take;
            }
        }
        return out;
    } else {
        /* PCM (WAV / AVI): read raw, convert to stereo 16 */
        int bps = s->bits / 8;
        int fb = bps * s->channels;
        unsigned char raw[CHUNK_FRAMES * 4];
        long want = (long) max * fb;
        long got, frames, i;
        if (want > (long) sizeof(raw)) {
            want = sizeof(raw);
        }
        got = src_audio_read(s, raw, want);
        frames = got / fb;
        for (i = 0; i < frames; i++) {
            int l, r;
            const unsigned char *q = raw + i * fb;
            if (bps == 1) {
                l = ((int) q[0] - 128) << 8;
                r = s->channels == 2 ? (((int) q[1] - 128) << 8) : l;
            } else {
                l = (short) rd16(q);
                r = s->channels == 2 ? (short) rd16(q + 2) : l;
            }
            dst[i * 2] = (short) l;
            dst[i * 2 + 1] = (short) r;
        }
        return (int) frames;
    }
}

/** Repositions the audio stream (and the MP3 decoder) to ms. */
static void src_seek(source *s, long ms) {
    if (ms < 0) ms = 0;
    if (s->type == T_MP3) {
        long bytes = s->size - s->mp3_first;
        long target = s->mp3_first;
        if (s->duration_ms > 0) {
            target = s->mp3_first + (long) ((double) bytes * ms / s->duration_ms);
        }
        if (target > s->size - 1) target = s->size - 1;
        s->mp3_pos = target;
        s->mp3_in_len = 0;
        s->mp3_pcm_n = s->mp3_pcm_at = 0;
        mp3dec_init(&s->dec);
    } else if (s->has_audio) {
        long fb = (s->bits / 8) * s->channels;
        long p = (long) ((double) ms * s->rate / 1000.0) * fb;
        if (p > s->audio_bytes) p = s->audio_bytes;
        s->apos = p;
    }
}

/* ====================================================================== */
/*                                the player                              */
/* ====================================================================== */

typedef struct {
    source src;
    int opened;
    volatile int state;
    volatile int err;               /* ST_ERROR detail: 1 no device, 2 format, 3 read */
    volatile int cmd_stop, cmd_pause;
    volatile long seek_ms;          /* >= 0: seek requested */
    volatile int volume;            /* 0..100 */
    volatile long pos_ms;
    pthread_t thread;
    int thread_live;

    /* video hand-off */
    pthread_mutex_t lock;
    int *frame;
    int fw, fh, maxw, maxh;
    volatile int frame_seq;
    int frame_taken;
    int cur_frame;                  /* index of the frame shown */
    volatile int frames_dropped;
} player;

static player P;
static int P_init;

static void p_init(void) {
    if (!P_init) {
        memset(&P, 0, sizeof(P));
        pthread_mutex_init(&P.lock, NULL);
        P.volume = 70;
        P.seek_ms = -1;
        P_init = 1;
    }
}

/* --- video frames ------------------------------------------------------- */

static int decode_video_frame(player *p, int index) {
#if defined(S100_HOST_TEST) || !ENABLE_JPEG
    (void) p; (void) index;
    return -1;
#else
    source *s = &p->src;
    unsigned char *data;
    long len;
    void *info;
    int rc = -1;
    if (index < 0 || index >= s->nvseg) {
        return -1;
    }
    len = s->vseg[index].len;
    if (len <= 0 || len > 4 * 1024 * 1024) {
        return -1;
    }
    data = (unsigned char *) malloc(len);
    if (data == NULL) {
        return -1;
    }
    fseek(s->f, s->vseg[index].off, SEEK_SET);
    if (fread(data, 1, len, s->f) != (size_t) len) {
        free(data);
        return -1;
    }
    info = JPEG_To_RGB_init();
    if (info != NULL) {
        int w, h;
        if (JPEG_To_RGB_decodeHeader(info, (char *) data, (int) len, &w, &h)) {
            struct jpeg_decompress_struct *ci = (struct jpeg_decompress_struct *) info;
            int denom = 1, ow, oh;
            w = (int) ci->image_width;
            h = (int) ci->image_height;
            while (denom < 8 && ((w + denom - 1) / denom > p->maxw || (h + denom - 1) / denom > p->maxh)) {
                denom *= 2;
            }
            ci->scale_num = 1;
            ci->scale_denom = denom;
            jm_jpeg_calc_output_dimensions(ci);
            ow = (int) ci->output_width;
            oh = (int) ci->output_height;
            if (ow > 0 && oh > 0) {
                unsigned short *px = (unsigned short *) malloc((size_t) ow * oh * 2);
                if (px != NULL && JPEG_To_RGB_decodeData2(info, (char *) px, 2, 0, 0, ow, oh) != 0) {
                    int cw = ow > p->maxw ? p->maxw : ow;
                    int ch = oh > p->maxh ? p->maxh : oh;
                    int x, y;
                    pthread_mutex_lock(&p->lock);
                    if (p->frame != NULL) {
                        for (y = 0; y < ch; y++) {
                            const unsigned short *row = px + (long) y * ow;
                            int *dst = p->frame + (long) y * cw;
                            for (x = 0; x < cw; x++) {
                                unsigned v = row[x];
                                int r = (v >> 11) & 0x1f, g = (v >> 5) & 0x3f, b = v & 0x1f;
                                dst[x] = ((r << 3) | (r >> 2)) << 16 | ((g << 2) | (g >> 4)) << 8
                                       | ((b << 3) | (b >> 2));
                            }
                        }
                        p->fw = cw;
                        p->fh = ch;
                        p->frame_seq++;
                        rc = 0;
                    }
                    pthread_mutex_unlock(&p->lock);
                }
                free(px);
            }
        }
        JPEG_To_RGB_free(info);
    }
    free(data);
    return rc;
#endif
}

/** Shows the frame due at ms (skipping late ones). */
static void video_catch_up(player *p, long ms) {
    source *s = &p->src;
    int target;
    if (!s->has_video) {
        return;
    }
    target = (int) (ms * s->fps / 1000.0);
    if (target >= s->nvseg) {
        target = s->nvseg - 1;
    }
    if (target > p->cur_frame || p->cur_frame < 0) {
        if (p->cur_frame >= 0 && target > p->cur_frame + 1) {
            p->frames_dropped += target - p->cur_frame - 1;
        }
        decode_video_frame(p, target);
        p->cur_frame = target;
    }
}

/* --- the thread ---------------------------------------------------------- */

typedef struct {
    short buf[CHUNK_FRAMES * 4 * 2];   /* stereo source frames */
    int count;
    unsigned pos_fp, step_fp;
    int eof;
} resampler;

static void rs_reset(resampler *r, int src_rate) {
    r->count = 0;
    r->pos_fp = 0;
    r->eof = 0;
    r->step_fp = (unsigned) (((unsigned long long) src_rate << 16) / OUT_RATE);
    if (r->step_fp == 0) r->step_fp = 1 << 16;
}

/** Produces up to max output frames; 0 = source exhausted. */
static int rs_output(player *p, resampler *r, short *out, int max) {
    int n = 0;
    int gain = p->volume * p->volume;         /* 0..10000, perceptual-ish */
    if (p->src.type == T_MP3 && r->step_fp != (unsigned) (((unsigned long long) p->src.rate << 16) / OUT_RATE)) {
        r->step_fp = (unsigned) (((unsigned long long) p->src.rate << 16) / OUT_RATE);
    }
    while (n < max) {
        unsigned idx = r->pos_fp >> 16;
        if ((int) idx + 1 >= r->count) {
            int got, keep;
            if (r->eof) {
                break;
            }
            keep = r->count - (int) idx;
            if (keep < 0) keep = 0;
            if (keep > 0 && (int) idx > 0) {
                memmove(r->buf, r->buf + idx * 2, keep * 2 * sizeof(short));
            }
            r->count = keep;
            r->pos_fp -= idx << 16;
            got = src_fill(&p->src, r->buf + r->count * 2, CHUNK_FRAMES * 4 - r->count);
            if (got <= 0) {
                r->eof = 1;
                if (r->count >= 1 && (int) (r->pos_fp >> 16) < r->count) {
                    /* flush the last sample without interpolation */
                    r->buf[r->count * 2] = r->buf[(r->count - 1) * 2];
                    r->buf[r->count * 2 + 1] = r->buf[(r->count - 1) * 2 + 1];
                    r->count++;
                    r->eof = 2;
                }
                if (got < 0) {
                    return -1;
                }
                if (r->eof == 1) {
                    break;
                }
                continue;
            }
            r->count += got;
            continue;
        }
        {
            unsigned frac = r->pos_fp & 0xffff;
            const short *a = r->buf + idx * 2;
            int l = a[0] + (((a[2] - a[0]) * (int) frac) >> 16);
            int rr = a[1] + (((a[3] - a[1]) * (int) frac) >> 16);
            l = (l * gain) / 10000;
            rr = (rr * gain) / 10000;
            out[n * 2] = (short) l;
            out[n * 2 + 1] = (short) rr;
            n++;
            r->pos_fp += r->step_fp;
        }
        if (r->eof == 2 && (int) (r->pos_fp >> 16) + 1 >= r->count) {
            r->eof = 1;
            break;
        }
    }
    return n;
}

static void *player_main(void *arg) {
    player *p = (player *) arg;
    source *s = &p->src;
    pcm_out out;
    resampler rs;
    short chunk[CHUNK_FRAMES * OUT_CH];
    long base_ms = 0;              /* stream time at the last (re)start */
    long wall_start = 0;           /* video-only clock */
    int have_pcm = 0;
    (void) arg;
    out.fd = -1;
    if (s->has_audio) {
        int rc = pcm_open_out(&out);
        if (rc != 0) {
            p->err = 1;
            p->state = ST_ERROR;
            return NULL;
        }
        have_pcm = 1;
    }
    rs_reset(&rs, s->rate > 0 ? s->rate : OUT_RATE);
    p->cur_frame = -1;
    p->state = ST_PLAYING;
    wall_start = ms_now();
    while (!p->cmd_stop) {
        long ms;
        if (p->seek_ms >= 0) {
            long to = p->seek_ms;
            p->seek_ms = -1;
            if (to > s->duration_ms) to = s->duration_ms;
            if (have_pcm) {
                pcm_drop(&out);
            }
            src_seek(s, to);
            rs_reset(&rs, s->rate);
            out.written = 0;
            base_ms = to;
            wall_start = ms_now() - to;
            p->cur_frame = -1;
            p->pos_ms = to;
            if (s->has_video && !s->has_audio) {
                video_catch_up(p, to);
            }
        }
        if (p->cmd_pause) {
            if (p->state == ST_PLAYING) {
                p->state = ST_PAUSED;
                if (have_pcm) {
                    /* drop what the driver holds, and forget it was written */
                    long d = pcm_delay(&out);
                    out.written -= d;
                    if (out.written < 0) out.written = 0;
                    pcm_drop(&out);
                }
            }
            usleep(50000);
            if (!s->has_audio) {
                wall_start = ms_now() - p->pos_ms;
            }
            continue;
        }
        if (p->state == ST_PAUSED) {
            p->state = ST_PLAYING;
        }
        if (s->has_audio) {
            int n = rs_output(p, &rs, chunk, CHUNK_FRAMES);
            if (n < 0) {
                p->err = 3;
                p->state = ST_ERROR;
                break;
            }
            if (n == 0) {
                /* let the tail play out */
                long left;
                while ((left = pcm_delay(&out)) > 0 && !p->cmd_stop && p->seek_ms < 0) {
                    usleep(20000);
                }
                if (p->seek_ms >= 0) {
                    continue;
                }
                p->pos_ms = s->duration_ms;
                p->state = ST_ENDED;
                break;
            }
            if (pcm_write_out(&out, chunk, (unsigned) n) != 0) {
                p->err = 1;
                p->state = ST_ERROR;
                break;
            }
            ms = base_ms + (out.written - pcm_delay(&out)) * 1000L / OUT_RATE;
            if (ms < base_ms) ms = base_ms;
            p->pos_ms = ms;
            video_catch_up(p, ms);
        } else {
            /* video only: pace on the wall clock */
            long next_ms;
            ms = ms_now() - wall_start;
            p->pos_ms = ms;
            if (p->cur_frame >= s->nvseg - 1 && ms >= s->duration_ms) {
                p->state = ST_ENDED;
                break;
            }
            video_catch_up(p, ms);
            next_ms = (long) ((p->cur_frame + 1) * 1000.0 / s->fps);
            ms = ms_now() - wall_start;
            if (next_ms > ms) {
                long wait = next_ms - ms;
                usleep((wait > 100 ? 100 : wait) * 1000);
            }
        }
    }
    if (have_pcm) {
        pcm_close_out(&out);
    }
    if (p->state == ST_PLAYING || p->state == ST_PAUSED) {
        p->state = ST_STOPPED;
    }
    return NULL;
}

/* --- API used by the KNI layer and the host test ------------------------- */

static void media_stop(void) {
    p_init();
    if (P.thread_live) {
        P.cmd_stop = 1;
        pthread_join(P.thread, NULL);
        P.thread_live = 0;
    }
    P.cmd_stop = 0;
    P.cmd_pause = 0;
    P.seek_ms = -1;
    if (P.state != ST_ERROR) {
        P.state = ST_STOPPED;
    }
}

static int media_open(const char *path, int maxw, int maxh) {
    int rc;
    p_init();
    media_stop();
    if (P.opened) {
        src_close(&P.src);
        P.opened = 0;
    }
    pthread_mutex_lock(&P.lock);
    free(P.frame);
    P.frame = NULL;
    P.fw = P.fh = 0;
    P.frame_seq = 0;
    P.frame_taken = 0;
    pthread_mutex_unlock(&P.lock);
    P.maxw = maxw > 0 ? maxw : 240;
    P.maxh = maxh > 0 ? maxh : 320;
    P.state = ST_STOPPED;
    P.err = 0;
    P.pos_ms = 0;
    P.frames_dropped = 0;
    rc = src_open(&P.src, path);
    if (rc != 0) {
        return rc;
    }
#if defined(S100_HOST_TEST) || !ENABLE_JPEG
    if (P.src.has_video) {
        P.src.has_video = 0;          /* no decoder: sound only */
        if (!P.src.has_audio) {
            src_close(&P.src);
            return -4;
        }
    }
#endif
    if (P.src.has_video) {
        P.frame = (int *) malloc(sizeof(int) * P.maxw * P.maxh);
        if (P.frame == NULL) {
            src_close(&P.src);
            return -1;
        }
    }
    P.opened = 1;
    return 0;
}

static int media_play(void) {
    p_init();
    if (!P.opened) {
        return -1;
    }
    if (P.thread_live && (P.state == ST_PLAYING || P.state == ST_PAUSED)) {
        P.cmd_pause = 0;              /* resume */
        return 0;
    }
    if (P.thread_live) {
        pthread_join(P.thread, NULL); /* finished on its own: reap it */
        P.thread_live = 0;
    }
    if (P.state == ST_ENDED || P.state == ST_ERROR) {
        src_seek(&P.src, 0);
        P.pos_ms = 0;
    }
    P.cmd_stop = 0;
    P.cmd_pause = 0;
    P.err = 0;
    P.state = ST_PLAYING;
    if (pthread_create(&P.thread, NULL, player_main, &P) != 0) {
        P.state = ST_ERROR;
        P.err = 1;
        return -2;
    }
    P.thread_live = 1;
    return 0;
}

static void media_close(void) {
    p_init();
    media_stop();
    if (P.opened) {
        src_close(&P.src);
        P.opened = 0;
    }
    pthread_mutex_lock(&P.lock);
    free(P.frame);
    P.frame = NULL;
    pthread_mutex_unlock(&P.lock);
    P.state = ST_STOPPED;
}

/* ====================================================================== */
/*                                   KNI                                  */
/* ====================================================================== */

#ifndef S100_HOST_TEST

extern char *s100_string_arg(int index);

/* --- Sys.nMediaOpen(String path, int maxW, int maxH) -> int ------------ */
KNIEXPORT KNI_RETURNTYPE_INT
KNIDECL(com_sun_midp_appmanager_Sys_nMediaOpen) {
    char *path = s100_string_arg(1);
    int maxw = KNI_GetParameterAsInt(2);
    int maxh = KNI_GetParameterAsInt(3);
    int rc = -1;
    if (path != NULL) {
        rc = media_open(path, maxw, maxh);
    }
    free(path);
    KNI_ReturnInt(rc);
}

/* --- Sys.nMediaInfo(int what) -> int ------------------------------------ */
KNIEXPORT KNI_RETURNTYPE_INT
KNIDECL(com_sun_midp_appmanager_Sys_nMediaInfo) {
    int what = KNI_GetParameterAsInt(1);
    int r = 0;
    p_init();
    if (P.opened) {
        switch (what) {
        case 0: r = (int) P.src.duration_ms; break;
        case 1: r = P.src.w; break;
        case 2: r = P.src.h; break;
        case 3: r = P.src.has_audio; break;
        case 4: r = P.src.has_video; break;
        case 5: r = P.src.rate; break;
        case 6: r = P.src.channels; break;
        case 7: r = P.src.bitrate_kbps; break;
        case 8: r = P.src.type; break;
        case 9: r = P.src.nvseg; break;
        case 10: r = (int) (P.src.fps * 100); break;
        case 11: r = P.frames_dropped; break;
        }
    }
    KNI_ReturnInt(r);
}

/* --- Sys.nMediaTag(int which) -> String (0 title, 1 artist, 2 album) ---- */
KNIEXPORT KNI_RETURNTYPE_OBJECT
KNIDECL(com_sun_midp_appmanager_Sys_nMediaTag) {
    int which = KNI_GetParameterAsInt(1);
    const char *t = NULL;
    KNI_StartHandles(1);
    KNI_DeclareHandle(result);
    p_init();
    if (P.opened) {
        t = which == 0 ? P.src.title : (which == 1 ? P.src.artist : P.src.album);
    }
    if (t != NULL && t[0] != 0) {
        KNI_NewStringUTF(t, result);
    } else {
        KNI_ReleaseHandle(result);
    }
    KNI_EndHandlesAndReturnObject(result);
}

/* --- Sys.nMediaPlay() -> int --------------------------------------------- */
KNIEXPORT KNI_RETURNTYPE_INT
KNIDECL(com_sun_midp_appmanager_Sys_nMediaPlay) {
    KNI_ReturnInt(media_play());
}

/* --- Sys.nMediaPause() --------------------------------------------------- */
KNIEXPORT KNI_RETURNTYPE_VOID
KNIDECL(com_sun_midp_appmanager_Sys_nMediaPause) {
    p_init();
    if (P.thread_live) {
        P.cmd_pause = 1;
    }
    KNI_ReturnVoid();
}

/* --- Sys.nMediaStop() ---------------------------------------------------- */
KNIEXPORT KNI_RETURNTYPE_VOID
KNIDECL(com_sun_midp_appmanager_Sys_nMediaStop) {
    media_stop();
    KNI_ReturnVoid();
}

/* --- Sys.nMediaClose() --------------------------------------------------- */
KNIEXPORT KNI_RETURNTYPE_VOID
KNIDECL(com_sun_midp_appmanager_Sys_nMediaClose) {
    media_close();
    KNI_ReturnVoid();
}

/* --- Sys.nMediaSeek(int ms) ---------------------------------------------- */
KNIEXPORT KNI_RETURNTYPE_VOID
KNIDECL(com_sun_midp_appmanager_Sys_nMediaSeek) {
    int ms = KNI_GetParameterAsInt(1);
    p_init();
    if (P.opened) {
        if (ms < 0) ms = 0;
        if (P.thread_live) {
            P.seek_ms = ms;
        } else {
            src_seek(&P.src, ms);
            P.pos_ms = ms;
            if (P.state == ST_ENDED) {
                P.state = ST_STOPPED;
            }
        }
    }
    KNI_ReturnVoid();
}

/* --- Sys.nMediaPos() -> int ms ------------------------------------------- */
KNIEXPORT KNI_RETURNTYPE_INT
KNIDECL(com_sun_midp_appmanager_Sys_nMediaPos) {
    p_init();
    KNI_ReturnInt((int) P.pos_ms);
}

/* --- Sys.nMediaState() -> int (0 stopped 1 playing 2 paused 3 ended -1 error) */
KNIEXPORT KNI_RETURNTYPE_INT
KNIDECL(com_sun_midp_appmanager_Sys_nMediaState) {
    p_init();
    if (P.thread_live && P.state != ST_PLAYING && P.state != ST_PAUSED) {
        /* the thread finished: reap it so play() can restart */
        pthread_join(P.thread, NULL);
        P.thread_live = 0;
    }
    KNI_ReturnInt(P.state == ST_ERROR ? -P.err - 1 : P.state);
}

/* --- Sys.nMediaVolume(int pct) ------------------------------------------- */
KNIEXPORT KNI_RETURNTYPE_VOID
KNIDECL(com_sun_midp_appmanager_Sys_nMediaVolume) {
    int v = KNI_GetParameterAsInt(1);
    p_init();
    P.volume = v < 0 ? 0 : (v > 100 ? 100 : v);
    KNI_ReturnVoid();
}

/* --- Sys.nMediaFrame(int[] out) -> (w << 16) | h, 0 when nothing new ----- */
KNIEXPORT KNI_RETURNTYPE_INT
KNIDECL(com_sun_midp_appmanager_Sys_nMediaFrame) {
    int rc = 0;
    KNI_StartHandles(1);
    KNI_DeclareHandle(arr);
    KNI_GetParameterAsObject(1, arr);
    p_init();
    if (!KNI_IsNullHandle(arr)) {
        pthread_mutex_lock(&P.lock);
        if (P.frame != NULL && P.frame_seq != P.frame_taken && P.fw > 0 && P.fh > 0
                && KNI_GetArrayLength(arr) >= P.fw * P.fh) {
            KNI_SetRawArrayRegion(arr, 0, sizeof(int) * P.fw * P.fh, (jbyte *) P.frame);
            P.frame_taken = P.frame_seq;
            rc = (P.fw << 16) | P.fh;
        }
        pthread_mutex_unlock(&P.lock);
    }
    KNI_EndHandles();
    KNI_ReturnInt(rc);
}

#endif /* !S100_HOST_TEST */

/* ====================================================================== */
/*                                host test                               */
/* ====================================================================== */

#ifdef S100_HOST_TEST
/*
 * gcc -DS100_HOST_TEST -DS100_NO_ALSA -O2 -o s100_media_test s100_media.c -lm -lpthread
 * ./s100_media_test <file.wav|mp3|avi> [out.pcm]
 * Prints what the demuxer found and decodes the sound track to raw
 * 48 kHz stereo S16 (play with: aplay -f S16_LE -r 48000 -c 2 out.pcm).
 */
int main(int argc, char **argv) {
    int rc;
    long last = -1;
    int seeked = 0;
    if (argc < 2) {
        fprintf(stderr, "usage: %s file [out.pcm]\n", argv[0]);
        return 2;
    }
    host_pcm = fopen(argc > 2 ? argv[2] : "/dev/null", "wb");
    rc = media_open(argv[1], 240, 282);
    printf("open rc=%d\n", rc);
    if (rc != 0) {
        return 1;
    }
    printf("type=%d audio=%d (%d Hz, %d ch, %d bit) video=%d (%dx%d, %.2f fps, %d frames) "
           "duration=%ld ms bitrate=%d kbps\ntitle='%s' artist='%s' album='%s'\n",
           P.src.type, P.src.has_audio, P.src.rate, P.src.channels, P.src.bits,
           P.src.has_video, P.src.w, P.src.h, P.src.fps, P.src.nvseg,
           P.src.duration_ms, P.src.bitrate_kbps, P.src.title, P.src.artist, P.src.album);
    if (!P.src.has_audio) {
        printf("no sound track to decode\n");
        media_close();
        return 0;
    }
    P.volume = 100;
    rc = media_play();
    printf("play rc=%d\n", rc);
    while (P.state == ST_PLAYING || P.state == ST_PAUSED) {
        if (P.pos_ms / 1000 != last) {
            last = P.pos_ms / 1000;
            printf("\rpos=%ld ms", P.pos_ms);
            fflush(stdout);
        }
        if (P.pos_ms > 3000 && !seeked) {
            P.seek_ms = 1000;              /* exercise seeking once */
            seeked = 1;
        }
        usleep(10000);
    }
    printf("\nend state=%d err=%d\n", P.state, P.err);
    media_close();
    if (host_pcm) fclose(host_pcm);
    return 0;
}
#endif
