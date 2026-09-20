/*
 * s100_native.c - the native side of com.sun.midp.appmanager.Sys.
 *
 * The S100 shell (Series 40 style AMS UI for the JioPhone port) needs a
 * few things CLDC/MIDP 2.1 without JSR-75/135 cannot do from Java:
 *
 *   - list directories and stat/mkdir/remove/rename/copy absolute paths
 *     (File manager, camera album)
 *   - run phone commands and background processes (s100_net.sh for
 *     Wi-Fi/hotspot/USB/Bluetooth, mm-qcamera-app for the camera)
 *   - turn the NV21 frames mm-qcamera-app dumps into RGB for the
 *     viewfinder and into JPEG files for photos (baseline 4:2:0 encoder)
 *   - decode JPEG files scaled down for the image viewer (IJG library
 *     that the MIDP build links when USE_JPEG=true)
 *   - record a Motion-JPEG AVI from the preview frames on a helper thread
 *
 * Java-side thin wrappers live in Sys.java; nothing here touches the VM
 * from the recorder thread. Blocking calls (nExec) freeze the whole VM
 * (CLDC-HI green threads), so the Java side keeps them short and uses
 * nSpawn + polling for anything slow.
 *
 * Compiled by the MIDP AMS makefile through os/port/ams/appmanager_ui/
 * lib.gmk. With -DS100_HOST_TEST it builds as a standalone test program
 * for the JPEG/AVI code (see os/scripts/check_native.sh).
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <math.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <signal.h>
#include <dirent.h>
#include <pthread.h>
#include <poll.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/time.h>
#include <sys/statfs.h>

#ifndef S100_HOST_TEST
#include <kni.h>
#include <sni.h>
#if ENABLE_JPEG
#include <jpeglib.h>
#include <jpegdecoder.h>
#endif
#endif

/* ====================================================================== */
/*                               small helpers                            */
/* ====================================================================== */

static long file_size(const char *path) {
    struct stat st;
    if (stat(path, &st) != 0) {
        return -1;
    }
    return (long) st.st_size;
}

/** Reads a whole file; *len receives the size. NULL on failure. */
static unsigned char *read_file(const char *path, long *len) {
    FILE *f = fopen(path, "rb");
    long n;
    unsigned char *buf;
    if (f == NULL) {
        return NULL;
    }
    if (fseek(f, 0, SEEK_END) != 0 || (n = ftell(f)) < 0) {
        fclose(f);
        return NULL;
    }
    fseek(f, 0, SEEK_SET);
    buf = (unsigned char *) malloc(n > 0 ? n : 1);
    if (buf == NULL) {
        fclose(f);
        return NULL;
    }
    if (n > 0 && fread(buf, 1, n, f) != (size_t) n) {
        free(buf);
        fclose(f);
        return NULL;
    }
    fclose(f);
    *len = n;
    return buf;
}

static long now_ms(void) {
    struct timeval tv;
    gettimeofday(&tv, NULL);
    return (long) (tv.tv_sec * 1000L + tv.tv_usec / 1000);
}

/* ====================================================================== */
/*                          YUV 4:2:0 frame handling                       */
/* ====================================================================== */

/*
 * A frame as mm-qcamera-app dumps it: w x h luma plane followed by the
 * chroma at half resolution, either interleaved (fmt 0 = NV21: V first,
 * the Qualcomm default; 1 = NV12: U first) or planar (2 = YV12: V plane
 * then U plane; 3 = I420: U then V).
 */
typedef struct {
    int w, h;
    unsigned char *y;
    unsigned char *uv;
    int fmt;
} yuv_frame;

/** Chroma sample of the frame at luma position (sx, sy). */
static void yuv_chroma(const yuv_frame *fr, int sx, int sy, int *U, int *V) {
    if (fr->fmt >= 2) {
        int cw = fr->w / 2;
        int i = (sy >> 1) * cw + (sx >> 1);
        int plane = cw * (fr->h / 2);
        if (fr->fmt == 2) { *V = fr->uv[i]; *U = fr->uv[plane + i]; }
        else              { *U = fr->uv[i]; *V = fr->uv[plane + i]; }
    } else {
        const unsigned char *c = fr->uv + (sy >> 1) * fr->w + (sx & ~1);
        if (fr->fmt == 0) { *V = c[0]; *U = c[1]; }
        else              { *U = c[0]; *V = c[1]; }
    }
}

static int yuv_load(const char *path, int w, int h, int fmt, yuv_frame *fr) {
    long len;
    unsigned char *buf = read_file(path, &len);
    if (buf == NULL) {
        return -1;
    }
    if (len < (long) w * h * 3 / 2) {
        free(buf);
        return -2;                    /* torn/short frame */
    }
    fr->w = w;
    fr->h = h;
    fr->y = buf;
    fr->uv = buf + w * h;
    fr->fmt = fmt;
    return 0;
}

static void yuv_free(yuv_frame *fr) {
    free(fr->y);
    fr->y = fr->uv = NULL;
}

#define CLAMP8(v) ((v) < 0 ? 0 : ((v) > 255 ? 255 : (v)))

static int yuv_rgb(int Y, int U, int V) {
    int c = Y - 16, d = U - 128, e = V - 128;
    int r = (298 * c + 409 * e + 128) >> 8;
    int g = (298 * c - 100 * d - 208 * e + 128) >> 8;
    int b = (298 * c + 516 * d + 128) >> 8;
    return (CLAMP8(r) << 16) | (CLAMP8(g) << 8) | CLAMP8(b);
}

/*
 * Maps a pixel of the rotated (and possibly mirrored) picture back to the
 * sensor frame. rot is 0/90/180/270 clockwise; rw/rh are the rotated
 * dimensions (h x w for 90/270).
 */
static void rot_map(int rot, int mirror, int w, int h, int rx, int ry,
                    int rw, int *sx, int *sy) {
    if (mirror) {
        rx = rw - 1 - rx;
    }
    switch (rot) {
    case 90:  *sx = ry;         *sy = h - 1 - rx; break;
    case 180: *sx = w - 1 - rx; *sy = h - 1 - ry; break;
    case 270: *sx = w - 1 - ry; *sy = rx;         break;
    default:  *sx = rx;         *sy = ry;         break;
    }
}

/*
 * Nearest-neighbour "fill and centre-crop" of the rotated frame into an
 * ow x oh RGB buffer (0xRRGGBB ints), for the viewfinder.
 */
static void yuv_to_rgb_fit(const yuv_frame *fr, int rot, int mirror,
                           int *out, int ow, int oh) {
    int rw = (rot == 90 || rot == 270) ? fr->h : fr->w;
    int rh = (rot == 90 || rot == 270) ? fr->w : fr->h;
    /* 16.16 fixed point scale: source pixels per output pixel */
    long sx_ = ((long) rw << 16) / ow;
    long sy_ = ((long) rh << 16) / oh;
    long step = sx_ < sy_ ? sx_ : sy_;          /* fill: smallest step */
    long x0 = ((long) rw << 16) / 2 - step * ow / 2;
    long y0 = ((long) rh << 16) / 2 - step * oh / 2;
    int *xmap = (int *) malloc(sizeof(int) * ow);
    int ox, oy;
    if (xmap == NULL) {
        return;
    }
    for (ox = 0; ox < ow; ox++) {
        long v = (x0 + step * ox) >> 16;
        xmap[ox] = v < 0 ? 0 : (v >= rw ? rw - 1 : (int) v);
    }
    for (oy = 0; oy < oh; oy++) {
        long v = (y0 + step * oy) >> 16;
        int ry = v < 0 ? 0 : (v >= rh ? rh - 1 : (int) v);
        int *row = out + oy * ow;
        for (ox = 0; ox < ow; ox++) {
            int sx, sy, Y, U, V;
            rot_map(rot, mirror, fr->w, fr->h, xmap[ox], ry, rw, &sx, &sy);
            Y = fr->y[sy * fr->w + sx];
            yuv_chroma(fr, sx, sy, &U, &V);
            row[ox] = yuv_rgb(Y, U, V);
        }
    }
    free(xmap);
}

/*
 * Planar 4:2:0 picture (separate Y, U, V planes), the encoder's input.
 * Produced from a frame by optional 2^n box downscale + rotation/mirror.
 */
typedef struct {
    int w, h;                 /* luma size (even) */
    unsigned char *y, *u, *v; /* u/v are (w/2) x (h/2) */
} planar;

static void planar_free(planar *p) {
    free(p->y); free(p->u); free(p->v);
    p->y = p->u = p->v = NULL;
}

static int planar_from_yuv(const yuv_frame *fr, int div, int rot, int mirror,
                           planar *p) {
    int sw = fr->w / div, sh = fr->h / div;       /* scaled source size */
    int rw = (rot == 90 || rot == 270) ? sh : sw;
    int rh = (rot == 90 || rot == 270) ? sw : sh;
    int x, y;
    rw &= ~1; rh &= ~1;
    p->w = rw; p->h = rh;
    p->y = (unsigned char *) malloc(rw * rh);
    p->u = (unsigned char *) malloc((rw / 2) * (rh / 2));
    p->v = (unsigned char *) malloc((rw / 2) * (rh / 2));
    if (p->y == NULL || p->u == NULL || p->v == NULL) {
        planar_free(p);
        return -1;
    }
    for (y = 0; y < rh; y++) {
        for (x = 0; x < rw; x++) {
            int sx, sy, sum = 0, i, j;
            rot_map(rot, mirror, sw, sh, x, y, rw, &sx, &sy);
            sx *= div; sy *= div;
            for (j = 0; j < div; j++) {
                const unsigned char *r = fr->y + (sy + j) * fr->w + sx;
                for (i = 0; i < div; i++) {
                    sum += r[i];
                }
            }
            p->y[y * rw + x] = (unsigned char) (sum / (div * div));
        }
    }
    for (y = 0; y < rh / 2; y++) {
        for (x = 0; x < rw / 2; x++) {
            /* chroma: sample the frame's chroma at the rotated position */
            int sx, sy, us = 0, vs = 0, i, j, n = 0;
            rot_map(rot, mirror, sw, sh, x * 2, y * 2, rw, &sx, &sy);
            sx *= div; sy *= div;
            for (j = 0; j < div; j += 2) {
                for (i = 0; i < div; i += 2) {
                    int U, V;
                    yuv_chroma(fr, sx + i, sy + j, &U, &V);
                    us += U; vs += V;
                    n++;
                }
            }
            p->u[y * (rw / 2) + x] = (unsigned char) (us / n);
            p->v[y * (rw / 2) + x] = (unsigned char) (vs / n);
        }
    }
    return 0;
}

/* ====================================================================== */
/*                  baseline JPEG encoder (4:2:0, std tables)              */
/* ====================================================================== */

static const unsigned char ZIGZAG[64] = {
    0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5, 12, 19, 26, 33,
    40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28, 35, 42, 49, 56, 57, 50,
    43, 36, 29, 22, 15, 23, 30, 37, 44, 51, 58, 59, 52, 45, 38, 31, 39, 46,
    53, 60, 61, 54, 47, 55, 62, 63
};

static const unsigned char YQT[64] = {
    16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55,
    14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87, 80, 62,
    18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92,
    49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99
};

static const unsigned char UVQT[64] = {
    17, 18, 24, 47, 99, 99, 99, 99, 18, 21, 26, 66, 99, 99, 99, 99,
    24, 26, 56, 99, 99, 99, 99, 99, 47, 66, 99, 99, 99, 99, 99, 99,
    99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99,
    99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99
};

/* Annex K.3 Huffman table specifications (bits[1..16], values) */
static const unsigned char DC_L_BITS[17] = {0, 0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0};
static const unsigned char DC_L_VAL[12] = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
static const unsigned char DC_C_BITS[17] = {0, 0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0};
static const unsigned char DC_C_VAL[12] = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
static const unsigned char AC_L_BITS[17] = {0, 0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7d};
static const unsigned char AC_L_VAL[162] = {
    0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06,
    0x13, 0x51, 0x61, 0x07, 0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xa1, 0x08,
    0x23, 0x42, 0xb1, 0xc1, 0x15, 0x52, 0xd1, 0xf0, 0x24, 0x33, 0x62, 0x72,
    0x82, 0x09, 0x0a, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x25, 0x26, 0x27, 0x28,
    0x29, 0x2a, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45,
    0x46, 0x47, 0x48, 0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59,
    0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6a, 0x73, 0x74, 0x75,
    0x76, 0x77, 0x78, 0x79, 0x7a, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
    0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3,
    0xa4, 0xa5, 0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6,
    0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3, 0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9,
    0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda, 0xe1, 0xe2,
    0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf1, 0xf2, 0xf3, 0xf4,
    0xf5, 0xf6, 0xf7, 0xf8, 0xf9, 0xfa
};
static const unsigned char AC_C_BITS[17] = {0, 0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77};
static const unsigned char AC_C_VAL[162] = {
    0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21, 0x31, 0x06, 0x12, 0x41,
    0x51, 0x07, 0x61, 0x71, 0x13, 0x22, 0x32, 0x81, 0x08, 0x14, 0x42, 0x91,
    0xa1, 0xb1, 0xc1, 0x09, 0x23, 0x33, 0x52, 0xf0, 0x15, 0x62, 0x72, 0xd1,
    0x0a, 0x16, 0x24, 0x34, 0xe1, 0x25, 0xf1, 0x17, 0x18, 0x19, 0x1a, 0x26,
    0x27, 0x28, 0x29, 0x2a, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44,
    0x45, 0x46, 0x47, 0x48, 0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58,
    0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6a, 0x73, 0x74,
    0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,
    0x88, 0x89, 0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a,
    0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4,
    0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3, 0xc4, 0xc5, 0xc6, 0xc7,
    0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda,
    0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf2, 0xf3, 0xf4,
    0xf5, 0xf6, 0xf7, 0xf8, 0xf9, 0xfa
};

typedef struct {
    unsigned short code[256];
    unsigned char len[256];
} huff;

/** Canonical Huffman codes from a bits/values specification. */
static void huff_build(huff *h, const unsigned char *bits, const unsigned char *val) {
    int code = 0, k = 0, l, i;
    memset(h, 0, sizeof(*h));
    for (l = 1; l <= 16; l++) {
        for (i = 0; i < bits[l]; i++) {
            h->code[val[k]] = (unsigned short) code;
            h->len[val[k]] = (unsigned char) l;
            k++;
            code++;
        }
        code <<= 1;
    }
}

/* growable output buffer with a bit accumulator */
typedef struct {
    unsigned char *buf;
    size_t len, cap;
    unsigned int bits;
    int nbits;
    int err;
} jout;

static void jo_byte(jout *o, int b) {
    if (o->len + 1 > o->cap) {
        size_t nc = o->cap ? o->cap * 2 : 65536;
        unsigned char *nb = (unsigned char *) realloc(o->buf, nc);
        if (nb == NULL) {
            o->err = 1;
            return;
        }
        o->buf = nb;
        o->cap = nc;
    }
    o->buf[o->len++] = (unsigned char) b;
}

static void jo_word(jout *o, int w) {
    jo_byte(o, (w >> 8) & 0xff);
    jo_byte(o, w & 0xff);
}

static void jo_bits(jout *o, unsigned int code, int n) {
    o->bits |= (code & ((1u << n) - 1)) << (24 - o->nbits - n);
    o->nbits += n;
    while (o->nbits >= 8) {
        int b = (o->bits >> 16) & 0xff;
        jo_byte(o, b);
        if (b == 0xff) {
            jo_byte(o, 0);
        }
        o->bits = (o->bits << 8) & 0xffffffu;
        o->nbits -= 8;
    }
}

static void jo_flush_bits(jout *o) {
    if (o->nbits > 0) {
        jo_bits(o, 0x7f, 7);          /* pad with ones */
    }
    o->bits = 0;
    o->nbits = 0;
}

/* AAN float forward DCT on 8 values with stride */
static void fdct8(float *d, int stride) {
    float d0 = d[0], d1 = d[stride], d2 = d[2 * stride], d3 = d[3 * stride];
    float d4 = d[4 * stride], d5 = d[5 * stride], d6 = d[6 * stride], d7 = d[7 * stride];
    float tmp0 = d0 + d7, tmp7 = d0 - d7;
    float tmp1 = d1 + d6, tmp6 = d1 - d6;
    float tmp2 = d2 + d5, tmp5 = d2 - d5;
    float tmp3 = d3 + d4, tmp4 = d3 - d4;
    float tmp10 = tmp0 + tmp3, tmp13 = tmp0 - tmp3;
    float tmp11 = tmp1 + tmp2, tmp12 = tmp1 - tmp2;
    float z1, z2, z3, z4, z5, z11, z13;
    d[0] = tmp10 + tmp11;
    d[4 * stride] = tmp10 - tmp11;
    z1 = (tmp12 + tmp13) * 0.707106781f;
    d[2 * stride] = tmp13 + z1;
    d[6 * stride] = tmp13 - z1;
    tmp10 = tmp4 + tmp5;
    tmp11 = tmp5 + tmp6;
    tmp12 = tmp6 + tmp7;
    z5 = (tmp10 - tmp12) * 0.382683433f;
    z2 = tmp10 * 0.541196100f + z5;
    z4 = tmp12 * 1.306562965f + z5;
    z3 = tmp11 * 0.707106781f;
    z11 = tmp7 + z3;
    z13 = tmp7 - z3;
    d[5 * stride] = z13 + z2;
    d[3 * stride] = z13 - z2;
    d[stride] = z11 + z4;
    d[7 * stride] = z11 - z4;
}

typedef struct {
    float fdtbl[64];          /* natural order: 1/(q * aan scale) */
    unsigned char qz[64];     /* quant table in zigzag order (for DQT) */
    huff dc, ac;
} jcomp;

static void jcomp_init(jcomp *c, const unsigned char *base, int quality,
                       const unsigned char *dcb, const unsigned char *dcv,
                       const unsigned char *acb, const unsigned char *acv) {
    static const float aasf[8] = {
        1.0f * 2.828427125f, 1.387039845f * 2.828427125f,
        1.306562965f * 2.828427125f, 1.175875602f * 2.828427125f,
        1.0f * 2.828427125f, 0.785694958f * 2.828427125f,
        0.541196100f * 2.828427125f, 0.275899379f * 2.828427125f
    };
    int sf, i, r, k;
    unsigned char q[64];
    if (quality < 1) quality = 1;
    if (quality > 100) quality = 100;
    sf = quality < 50 ? 5000 / quality : 200 - quality * 2;
    for (i = 0; i < 64; i++) {
        int v = (base[i] * sf + 50) / 100;
        q[i] = (unsigned char) (v < 1 ? 1 : (v > 255 ? 255 : v));
    }
    for (r = 0, k = 0; r < 8; r++) {
        for (i = 0; i < 8; i++, k++) {
            c->fdtbl[k] = 1.0f / (q[k] * aasf[r] * aasf[i]);
        }
    }
    for (k = 0; k < 64; k++) {
        c->qz[k] = q[ZIGZAG[k]];
    }
    huff_build(&c->dc, dcb, dcv);
    huff_build(&c->ac, acb, acv);
}

/* number of bits needed for |v| (JPEG "category") */
static int jcat(int v) {
    int n = 0;
    if (v < 0) v = -v;
    while (v) { n++; v >>= 1; }
    return n;
}

/** Encodes one 8x8 block taken from plane p at (x, y) with stride; returns new DC. */
static int encode_block(jout *o, const jcomp *c, const unsigned char *p,
                        int stride, int pw, int ph, int x, int y, int prevdc) {
    float blk[64];
    int du[64];
    int i, j, k, dc, diff, run;
    for (j = 0; j < 8; j++) {
        int sy = y + j; if (sy >= ph) sy = ph - 1;
        for (i = 0; i < 8; i++) {
            int sx = x + i; if (sx >= pw) sx = pw - 1;
            blk[j * 8 + i] = (float) p[sy * stride + sx] - 128.0f;
        }
    }
    for (j = 0; j < 8; j++) fdct8(blk + j * 8, 1);
    for (i = 0; i < 8; i++) fdct8(blk + i, 8);
    for (k = 0; k < 64; k++) {
        float v = blk[ZIGZAG[k]] * c->fdtbl[ZIGZAG[k]];
        du[k] = (int) (v < 0 ? ceilf(v - 0.5f) : floorf(v + 0.5f));
    }
    dc = du[0];
    diff = dc - prevdc;
    if (diff == 0) {
        jo_bits(o, c->dc.code[0], c->dc.len[0]);
    } else {
        int cat = jcat(diff);
        jo_bits(o, c->dc.code[cat], c->dc.len[cat]);
        jo_bits(o, (unsigned) (diff < 0 ? diff - 1 : diff), cat);
    }
    run = 0;
    for (k = 1; k < 64; k++) {
        int v = du[k];
        if (v == 0) {
            run++;
            continue;
        }
        while (run >= 16) {
            jo_bits(o, c->ac.code[0xf0], c->ac.len[0xf0]);
            run -= 16;
        }
        {
            int cat = jcat(v);
            int sym = (run << 4) | cat;
            jo_bits(o, c->ac.code[sym], c->ac.len[sym]);
            jo_bits(o, (unsigned) (v < 0 ? v - 1 : v), cat);
        }
        run = 0;
    }
    if (run > 0) {
        jo_bits(o, c->ac.code[0], c->ac.len[0]);      /* EOB */
    }
    return dc;
}

static void write_dht(jout *o, int cls, int id, const unsigned char *bits,
                      const unsigned char *val) {
    int n = 0, i;
    for (i = 1; i <= 16; i++) n += bits[i];
    jo_word(o, 0xffc4);
    jo_word(o, 2 + 1 + 16 + n);
    jo_byte(o, (cls << 4) | id);
    for (i = 1; i <= 16; i++) jo_byte(o, bits[i]);
    for (i = 0; i < n; i++) jo_byte(o, val[i]);
}

/**
 * Encodes a planar 4:2:0 picture as a baseline JPEG into *out (malloc'ed).
 * Returns the length, or -1.
 */
static long jpeg_encode_planar(const planar *p, int quality,
                               unsigned char **out) {
    jcomp cy, cc;
    jout o;
    int mx, my, i, dcy = 0, dcu = 0, dcv = 0;
    int w = p->w, h = p->h, cw = w / 2;
    memset(&o, 0, sizeof(o));
    jcomp_init(&cy, YQT, quality, DC_L_BITS, DC_L_VAL, AC_L_BITS, AC_L_VAL);
    jcomp_init(&cc, UVQT, quality, DC_C_BITS, DC_C_VAL, AC_C_BITS, AC_C_VAL);

    jo_word(&o, 0xffd8);                              /* SOI */
    jo_word(&o, 0xffe0); jo_word(&o, 16);             /* APP0 JFIF */
    jo_byte(&o, 'J'); jo_byte(&o, 'F'); jo_byte(&o, 'I'); jo_byte(&o, 'F'); jo_byte(&o, 0);
    jo_byte(&o, 1); jo_byte(&o, 1); jo_byte(&o, 0);
    jo_word(&o, 1); jo_word(&o, 1); jo_byte(&o, 0); jo_byte(&o, 0);
    jo_word(&o, 0xffdb); jo_word(&o, 2 + 2 * 65);     /* DQT */
    jo_byte(&o, 0); for (i = 0; i < 64; i++) jo_byte(&o, cy.qz[i]);
    jo_byte(&o, 1); for (i = 0; i < 64; i++) jo_byte(&o, cc.qz[i]);
    jo_word(&o, 0xffc0); jo_word(&o, 17);             /* SOF0 */
    jo_byte(&o, 8); jo_word(&o, h); jo_word(&o, w); jo_byte(&o, 3);
    jo_byte(&o, 1); jo_byte(&o, 0x22); jo_byte(&o, 0);
    jo_byte(&o, 2); jo_byte(&o, 0x11); jo_byte(&o, 1);
    jo_byte(&o, 3); jo_byte(&o, 0x11); jo_byte(&o, 1);
    write_dht(&o, 0, 0, DC_L_BITS, DC_L_VAL);
    write_dht(&o, 1, 0, AC_L_BITS, AC_L_VAL);
    write_dht(&o, 0, 1, DC_C_BITS, DC_C_VAL);
    write_dht(&o, 1, 1, AC_C_BITS, AC_C_VAL);
    jo_word(&o, 0xffda); jo_word(&o, 12); jo_byte(&o, 3);   /* SOS */
    jo_byte(&o, 1); jo_byte(&o, 0x00);
    jo_byte(&o, 2); jo_byte(&o, 0x11);
    jo_byte(&o, 3); jo_byte(&o, 0x11);
    jo_byte(&o, 0); jo_byte(&o, 63); jo_byte(&o, 0);

    for (my = 0; my < h; my += 16) {
        for (mx = 0; mx < w; mx += 16) {
            dcy = encode_block(&o, &cy, p->y, w, w, h, mx, my, dcy);
            dcy = encode_block(&o, &cy, p->y, w, w, h, mx + 8, my, dcy);
            dcy = encode_block(&o, &cy, p->y, w, w, h, mx, my + 8, dcy);
            dcy = encode_block(&o, &cy, p->y, w, w, h, mx + 8, my + 8, dcy);
            dcu = encode_block(&o, &cc, p->u, cw, cw, h / 2, mx / 2, my / 2, dcu);
            dcv = encode_block(&o, &cc, p->v, cw, cw, h / 2, mx / 2, my / 2, dcv);
            if (o.err) {
                free(o.buf);
                return -1;
            }
        }
    }
    jo_flush_bits(&o);
    jo_word(&o, 0xffd9);                              /* EOI */
    if (o.err) {
        free(o.buf);
        return -1;
    }
    *out = o.buf;
    return (long) o.len;
}

static int write_whole(const char *path, const unsigned char *data, long len) {
    FILE *f = fopen(path, "wb");
    if (f == NULL) {
        return -1;
    }
    if (len > 0 && fwrite(data, 1, len, f) != (size_t) len) {
        fclose(f);
        unlink(path);
        return -1;
    }
    fclose(f);
    return 0;
}

/** Frame file -> JPEG file. Returns 0, or <0 (see yuv_load). */
static int yuv_file_to_jpeg(const char *yuv, int w, int h, int fmt, int rot,
                            int mirror, int div, int quality, const char *jpg) {
    yuv_frame fr;
    planar p;
    unsigned char *data;
    long n;
    int rc = yuv_load(yuv, w, h, fmt, &fr);
    if (rc != 0) {
        return rc;
    }
    if (div < 1) div = 1;
    rc = planar_from_yuv(&fr, div, rot, mirror, &p);
    yuv_free(&fr);
    if (rc != 0) {
        return -3;
    }
    n = jpeg_encode_planar(&p, quality, &data);
    planar_free(&p);
    if (n < 0) {
        return -3;
    }
    rc = write_whole(jpg, data, n);
    free(data);
    return rc == 0 ? 0 : -4;
}

/* ====================================================================== */
/*                       Motion-JPEG AVI recorder                          */
/* ====================================================================== */

typedef struct {
    FILE *f;
    long riff_size_pos, hdrl_frames_pos, strh_len_pos, movi_size_pos, movi_start;
    long audio_len_pos;
    long *idx_off;            /* chunk offsets relative to movi list */
    long *idx_len;
    int idx_n, idx_cap;
    int w, h, fps;
    int has_audio;            /* audio strl written in the header */
    long audio_bytes;
} avi;

static void put32(FILE *f, unsigned long v) {
    unsigned char b[4];
    b[0] = v & 0xff; b[1] = (v >> 8) & 0xff; b[2] = (v >> 16) & 0xff; b[3] = (v >> 24) & 0xff;
    fwrite(b, 1, 4, f);
}

static void put16(FILE *f, unsigned int v) {
    unsigned char b[2];
    b[0] = v & 0xff; b[1] = (v >> 8) & 0xff;
    fwrite(b, 1, 2, f);
}

static void put4cc(FILE *f, const char *s) {
    fwrite(s, 1, 4, f);
}

static int avi_open(avi *a, const char *path, int w, int h, int fps,
                    int audio_rate) {
    FILE *f = fopen(path, "wb");
    long hdrl_pos, strl_pos;
    memset(a, 0, sizeof(*a));
    if (f == NULL) {
        return -1;
    }
    a->f = f; a->w = w; a->h = h; a->fps = fps; a->has_audio = audio_rate > 0;
    put4cc(f, "RIFF"); a->riff_size_pos = ftell(f); put32(f, 0); put4cc(f, "AVI ");
    put4cc(f, "LIST"); hdrl_pos = ftell(f); put32(f, 0); put4cc(f, "hdrl");
    /* avih */
    put4cc(f, "avih"); put32(f, 56);
    put32(f, 1000000UL / fps);        /* microseconds per frame */
    put32(f, 0);                      /* max bytes per sec */
    put32(f, 0);                      /* padding granularity */
    put32(f, 0x10);                   /* flags: has index */
    a->hdrl_frames_pos = ftell(f); put32(f, 0);
    put32(f, 0);                      /* initial frames */
    put32(f, a->has_audio ? 2 : 1);   /* streams */
    put32(f, 0);                      /* suggested buffer */
    put32(f, w); put32(f, h);
    put32(f, 0); put32(f, 0); put32(f, 0); put32(f, 0);
    /* video strl */
    put4cc(f, "LIST"); strl_pos = ftell(f); put32(f, 0); put4cc(f, "strl");
    put4cc(f, "strh"); put32(f, 56);
    put4cc(f, "vids"); put4cc(f, "MJPG");
    put32(f, 0); put16(f, 0); put16(f, 0); put32(f, 0);
    put32(f, 1); put32(f, fps);       /* scale, rate */
    put32(f, 0);                      /* start */
    a->strh_len_pos = ftell(f); put32(f, 0);
    put32(f, 0);                      /* suggested buffer */
    put32(f, 10000);                  /* quality */
    put32(f, 0);                      /* sample size */
    put16(f, 0); put16(f, 0); put16(f, w); put16(f, h);
    put4cc(f, "strf"); put32(f, 40);
    put32(f, 40); put32(f, w); put32(f, h); put16(f, 1); put16(f, 24);
    put4cc(f, "MJPG"); put32(f, (unsigned long) w * h * 3);
    put32(f, 0); put32(f, 0); put32(f, 0); put32(f, 0);
    {
        long end = ftell(f);
        fseek(f, strl_pos, SEEK_SET); put32(f, end - strl_pos - 4); fseek(f, end, SEEK_SET);
    }
    if (a->has_audio) {
        put4cc(f, "LIST"); strl_pos = ftell(f); put32(f, 0); put4cc(f, "strl");
        put4cc(f, "strh"); put32(f, 56);
        put4cc(f, "auds"); put32(f, 1);
        put32(f, 0); put16(f, 0); put16(f, 0); put32(f, 0);
        put32(f, 1); put32(f, audio_rate);   /* scale, rate: samples/s */
        put32(f, 0);
        a->audio_len_pos = ftell(f); put32(f, 0);   /* length: patched at close */
        put32(f, 0); put32(f, 0xffffffffUL); put32(f, 2);
        put16(f, 0); put16(f, 0); put16(f, 0); put16(f, 0);
        put4cc(f, "strf"); put32(f, 18);
        put16(f, 1); put16(f, 1); put32(f, audio_rate); put32(f, audio_rate * 2);
        put16(f, 2); put16(f, 16); put16(f, 0);
        {
            long end = ftell(f);
            fseek(f, strl_pos, SEEK_SET); put32(f, end - strl_pos - 4); fseek(f, end, SEEK_SET);
        }
    }
    {
        long end = ftell(f);
        fseek(f, hdrl_pos, SEEK_SET); put32(f, end - hdrl_pos - 4); fseek(f, end, SEEK_SET);
    }
    put4cc(f, "LIST"); a->movi_size_pos = ftell(f); put32(f, 0); put4cc(f, "movi");
    a->movi_start = a->movi_size_pos + 4;
    return 0;
}

static int avi_chunk(avi *a, const char *id, const unsigned char *data, long len) {
    long pos = ftell(a->f);
    if (a->idx_n == a->idx_cap) {
        int nc = a->idx_cap ? a->idx_cap * 2 : 1024;
        long *no = (long *) realloc(a->idx_off, nc * sizeof(long));
        long *nl = (long *) realloc(a->idx_len, nc * sizeof(long));
        if (no == NULL || nl == NULL) {
            free(no); free(nl);
            return -1;
        }
        a->idx_off = no; a->idx_len = nl; a->idx_cap = nc;
    }
    /* offsets in idx1 are relative to the 'movi' fourcc */
    a->idx_off[a->idx_n] = pos - a->movi_start;
    a->idx_len[a->idx_n] = len;
    a->idx_n++;
    put4cc(a->f, id); put32(a->f, len);
    fwrite(data, 1, len, a->f);
    if (len & 1) {
        fputc(0, a->f);
    }
    return 0;
}

/** Finishes the file: index, sizes. frames = number of '00dc' chunks. */
static int avi_close(avi *a, int frames, int audio_rate) {
    FILE *f = a->f;
    long end, movi_end = ftell(f);
    int i;
    fseek(f, a->movi_size_pos, SEEK_SET); put32(f, movi_end - a->movi_size_pos - 4);
    fseek(f, movi_end, SEEK_SET);
    put4cc(f, "idx1"); put32(f, a->idx_n * 16);
    for (i = 0; i < a->idx_n; i++) {
        /* the audio chunk (if any) is the last one */
        int is_audio = a->has_audio && i == a->idx_n - 1 && a->audio_bytes > 0;
        put4cc(f, is_audio ? "01wb" : "00dc");
        put32(f, 0x10);                   /* AVIIF_KEYFRAME */
        put32(f, a->idx_off[i]);
        put32(f, a->idx_len[i]);
    }
    end = ftell(f);
    fseek(f, a->riff_size_pos, SEEK_SET); put32(f, end - 8);
    fseek(f, a->hdrl_frames_pos, SEEK_SET); put32(f, frames);
    fseek(f, a->strh_len_pos, SEEK_SET); put32(f, frames);
    if (a->has_audio) {
        fseek(f, a->audio_len_pos, SEEK_SET);
        put32(f, audio_rate > 0 ? a->audio_bytes / 2 : 0);   /* samples */
    }
    fclose(f);
    free(a->idx_off);
    free(a->idx_len);
    memset(a, 0, sizeof(*a));
    return 0;
}

/* --- the recorder thread ------------------------------------------------ */

typedef struct {
    char yuv[256];
    char out[256];
    int w, h, fmt, rot, mirror, div, quality, fps;
    volatile int stop;
    volatile int frames;
    volatile int running;
    volatile int error;
    int audio_rate;
    pthread_t thread;
} recorder;

static recorder rec;

static void *rec_main(void *arg) {
    recorder *r = (recorder *) arg;
    avi a;
    long interval = 1000 / (r->fps > 0 ? r->fps : 10);
    long next = now_ms();
    int rw = 0, rh = 0;
    (void) arg;
    /* first frame decides the size */
    {
        yuv_frame fr;
        int tries = 0;
        while (yuv_load(r->yuv, r->w, r->h, r->fmt, &fr) != 0) {
            if (++tries > 50 || r->stop) {
                r->error = 1;
                r->running = 0;
                return NULL;
            }
            usleep(100000);
        }
        rw = ((r->rot == 90 || r->rot == 270) ? fr.h : fr.w) / r->div & ~1;
        rh = ((r->rot == 90 || r->rot == 270) ? fr.w : fr.h) / r->div & ~1;
        yuv_free(&fr);
    }
    if (avi_open(&a, r->out, rw, rh, r->fps, r->audio_rate) != 0) {
        r->error = 2;
        r->running = 0;
        return NULL;
    }
    while (!r->stop) {
        yuv_frame fr;
        planar p;
        unsigned char *data;
        long n;
        long t = now_ms();
        if (t < next) {
            usleep((next - t) * 1000);
            continue;
        }
        next += interval;
        if (next < t - interval) {
            next = t;                     /* fell behind: resync */
        }
        if (yuv_load(r->yuv, r->w, r->h, r->fmt, &fr) != 0) {
            continue;                     /* torn frame, try the next tick */
        }
        if (planar_from_yuv(&fr, r->div, r->rot, r->mirror, &p) != 0) {
            yuv_free(&fr);
            continue;
        }
        yuv_free(&fr);
        n = jpeg_encode_planar(&p, r->quality, &data);
        planar_free(&p);
        if (n < 0) {
            continue;
        }
        if (avi_chunk(&a, "00dc", data, n) == 0) {
            r->frames++;
        }
        free(data);
    }
    /* optional audio: a WAV recorded alongside, appended as one chunk */
    if (r->audio_rate > 0) {
        char wav[300];
        long len;
        unsigned char *d;
        snprintf(wav, sizeof(wav), "%s.wav", r->out);
        d = read_file(wav, &len);
        if (d != NULL) {
            long off = 44;                /* canonical WAV header */
            if (len > 44 && memcmp(d, "RIFF", 4) == 0) {
                /* find the data chunk */
                long p = 12;
                while (p + 8 <= len) {
                    unsigned long cl = d[p + 4] | (d[p + 5] << 8) | (d[p + 6] << 16)
                        | ((unsigned long) d[p + 7] << 24);
                    if (memcmp(d + p, "data", 4) == 0) {
                        off = p + 8;
                        /* a killed tinycap leaves the size 0: take the rest */
                        if (cl > 0 && (long) cl < len - off) len = off + cl;
                        break;
                    }
                    p += 8 + cl + (cl & 1);
                }
            }
            if (len > off + 1600) {       /* more than a tenth of a second */
                avi_chunk(&a, "01wb", d + off, len - off);
                a.audio_bytes = len - off;
            }
            free(d);
        }
        unlink(wav);
    }
    avi_close(&a, r->frames, r->audio_rate);
    r->running = 0;
    return NULL;
}

static int rec_start(const char *yuv, int w, int h, int fmt, int rot,
                     int mirror, const char *out, int fps, int div,
                     int quality, int audio_rate) {
    if (rec.running) {
        return -1;
    }
    memset(&rec, 0, sizeof(rec));
    strncpy(rec.yuv, yuv, sizeof(rec.yuv) - 1);
    strncpy(rec.out, out, sizeof(rec.out) - 1);
    rec.w = w; rec.h = h; rec.fmt = fmt; rec.rot = rot; rec.mirror = mirror;
    rec.div = div < 1 ? 1 : div; rec.quality = quality; rec.fps = fps;
    rec.audio_rate = audio_rate;
    rec.running = 1;
    if (pthread_create(&rec.thread, NULL, rec_main, &rec) != 0) {
        rec.running = 0;
        return -2;
    }
    return 0;
}

static int rec_stop(void) {
    if (!rec.running && rec.thread == 0) {
        return rec.frames;
    }
    rec.stop = 1;
    pthread_join(rec.thread, NULL);
    rec.thread = 0;
    return rec.error ? -rec.error : rec.frames;
}

/* ====================================================================== */
/*                          processes and commands                        */
/* ====================================================================== */

static int last_exit_code = -1;

static void child_exec(const char *cmd) {
    int fd;
    /* drop the VM's descriptors (framebuffer, evdev, sockets) */
    for (fd = 3; fd < 1024; fd++) {
        close(fd);
    }
    execl("/system/bin/sh", "sh", "-c", cmd, (char *) NULL);
    execl("/bin/sh", "sh", "-c", cmd, (char *) NULL);
    _exit(127);
}

/**
 * Runs cmd through sh -c, captures stdout+stderr (up to max bytes) and
 * waits for it (kills it after timeout_ms). Returns a malloc'ed string.
 */
static char *run_capture(const char *cmd, int timeout_ms, size_t max) {
    int pfd[2];
    pid_t pid;
    char *buf;
    size_t len = 0;
    long deadline;
    int status = 0;
    if (pipe(pfd) != 0) {
        return NULL;
    }
    buf = (char *) malloc(max + 1);
    if (buf == NULL) {
        close(pfd[0]); close(pfd[1]);
        return NULL;
    }
    pid = fork();
    if (pid < 0) {
        close(pfd[0]); close(pfd[1]);
        free(buf);
        return NULL;
    }
    if (pid == 0) {
        int devnull = open("/dev/null", O_RDONLY);
        if (devnull >= 0) { dup2(devnull, 0); }
        dup2(pfd[1], 1);
        dup2(pfd[1], 2);
        child_exec(cmd);
    }
    close(pfd[1]);
    deadline = now_ms() + timeout_ms;
    for (;;) {
        struct pollfd pf;
        long left = deadline - now_ms();
        int r;
        if (left < 0) {
            kill(pid, SIGKILL);
            break;
        }
        pf.fd = pfd[0];
        pf.events = POLLIN;
        r = poll(&pf, 1, (int) left);
        if (r <= 0) {
            if (r < 0 && errno == EINTR) continue;
            kill(pid, SIGKILL);
            break;
        }
        {
            char tmp[4096];
            ssize_t n = read(pfd[0], tmp, sizeof(tmp));
            if (n <= 0) {
                break;                        /* EOF: child closed its output */
            }
            if (len < max) {
                size_t c = (size_t) n;
                if (len + c > max) c = max - len;
                memcpy(buf + len, tmp, c);
                len += c;
            }
        }
    }
    close(pfd[0]);
    while (waitpid(pid, &status, 0) < 0 && errno == EINTR) {
        /* retry */
    }
    last_exit_code = WIFEXITED(status) ? WEXITSTATUS(status) : -1;
    buf[len] = 0;
    return buf;
}

/** Starts cmd detached in its own session; returns the pid (= group id). */
static int spawn_detached(const char *cmd) {
    pid_t pid = fork();
    if (pid < 0) {
        return -1;
    }
    if (pid == 0) {
        int devnull;
        setsid();
        devnull = open("/dev/null", O_RDWR);
        if (devnull >= 0) {
            dup2(devnull, 0); dup2(devnull, 1); dup2(devnull, 2);
        }
        child_exec(cmd);
    }
    return (int) pid;
}

/** True while the process exists; reaps it once it has exited. */
static int proc_alive(int pid) {
    int status;
    pid_t r;
    if (pid <= 0) {
        return 0;
    }
    r = waitpid((pid_t) pid, &status, WNOHANG);
    if (r == 0) {
        return 1;
    }
    if (r < 0 && errno == ECHILD) {
        /* not our child (or already reaped): ask the kernel */
        return kill((pid_t) pid, 0) == 0;
    }
    return 0;
}

static int proc_kill(int pid, int group) {
    int rc;
    if (pid <= 0) {
        return 0;
    }
    rc = kill(group ? -(pid_t) pid : (pid_t) pid, SIGKILL);
    if (rc != 0 && group) {
        rc = kill((pid_t) pid, SIGKILL);
    }
    {
        int status;
        int i;
        for (i = 0; i < 20; i++) {
            pid_t r = waitpid((pid_t) pid, &status, WNOHANG);
            if (r != 0) break;
            usleep(10000);
        }
    }
    return rc == 0;
}

/* ====================================================================== */
/*                         file system helpers                             */
/* ====================================================================== */

/**
 * Directory listing: one line per entry, "t<TAB>size<TAB>mtime<TAB>name",
 * t = d (directory) / f (file) / o (other). NULL if it cannot be opened.
 */
static char *list_dir(const char *path) {
    DIR *d = opendir(path);
    struct dirent *e;
    char *buf;
    size_t len = 0, cap = 8192;
    char full[1024];
    size_t plen;
    if (d == NULL) {
        return NULL;
    }
    buf = (char *) malloc(cap);
    if (buf == NULL) {
        closedir(d);
        return NULL;
    }
    buf[0] = 0;
    plen = strlen(path);
    while ((e = readdir(d)) != NULL) {
        struct stat st;
        char line[1200];
        char t = 'o';
        long size = 0, mtime = 0;
        int n;
        if (strcmp(e->d_name, ".") == 0 || strcmp(e->d_name, "..") == 0) {
            continue;
        }
        if (plen + 1 + strlen(e->d_name) >= sizeof(full)) {
            continue;
        }
        snprintf(full, sizeof(full), "%s%s%s", path,
                 (plen > 0 && path[plen - 1] == '/') ? "" : "/", e->d_name);
        if (stat(full, &st) == 0) {
            if (S_ISDIR(st.st_mode)) t = 'd';
            else if (S_ISREG(st.st_mode)) t = 'f';
            size = (long) st.st_size;
            mtime = (long) st.st_mtime;
        }
        n = snprintf(line, sizeof(line), "%c\t%ld\t%ld\t%s\n", t, size, mtime, e->d_name);
        if (n <= 0) {
            continue;
        }
        if (len + n + 1 > cap) {
            char *nb;
            while (len + n + 1 > cap) cap *= 2;
            nb = (char *) realloc(buf, cap);
            if (nb == NULL) {
                break;
            }
            buf = nb;
        }
        memcpy(buf + len, line, n);
        len += n;
        buf[len] = 0;
    }
    closedir(d);
    return buf;
}

static int copy_file(const char *from, const char *to) {
    FILE *a = fopen(from, "rb"), *b;
    char buf[65536];
    size_t n;
    if (a == NULL) {
        return -1;
    }
    b = fopen(to, "wb");
    if (b == NULL) {
        fclose(a);
        return -1;
    }
    while ((n = fread(buf, 1, sizeof(buf), a)) > 0) {
        if (fwrite(buf, 1, n, b) != n) {
            fclose(a); fclose(b); unlink(to);
            return -1;
        }
    }
    fclose(a);
    if (fclose(b) != 0) {
        unlink(to);
        return -1;
    }
    return 0;
}

static int remove_path(const char *path) {
    struct stat st;
    if (lstat(path, &st) != 0) {
        return -1;
    }
    if (S_ISDIR(st.st_mode)) {
        return rmdir(path);
    }
    return unlink(path);
}

/* ====================================================================== */
/*                                 KNI                                    */
/* ====================================================================== */

#ifndef S100_HOST_TEST

/** Copies string parameter `index` to a malloc'ed UTF-8 buffer ("" for null). */
static char *string_arg(int index) {
    char *out = NULL;
    KNI_StartHandles(1);
    KNI_DeclareHandle(h);
    KNI_GetParameterAsObject(index, h);
    if (KNI_IsNullHandle(h)) {
        out = (char *) malloc(1);
        if (out) out[0] = 0;
    } else {
        jsize n = KNI_GetStringLength(h);
        jchar *u = (jchar *) malloc((n > 0 ? n : 1) * sizeof(jchar));
        if (u != NULL) {
            jsize i;
            size_t o = 0;
            out = (char *) malloc(n * 3 + 1);
            if (out != NULL) {
                KNI_GetStringRegion(h, 0, n, u);
                for (i = 0; i < n; i++) {
                    unsigned int c = u[i];
                    if (c < 0x80) {
                        out[o++] = (char) c;
                    } else if (c < 0x800) {
                        out[o++] = (char) (0xc0 | (c >> 6));
                        out[o++] = (char) (0x80 | (c & 0x3f));
                    } else {
                        out[o++] = (char) (0xe0 | (c >> 12));
                        out[o++] = (char) (0x80 | ((c >> 6) & 0x3f));
                        out[o++] = (char) (0x80 | (c & 0x3f));
                    }
                }
                out[o] = 0;
            }
            free(u);
        }
    }
    KNI_EndHandles();
    return out;
}

/* --- Sys.nList(String path) -> String ----------------------------------- */
KNIEXPORT KNI_RETURNTYPE_OBJECT
KNIDECL(com_sun_midp_appmanager_Sys_nList) {
    char *path = string_arg(1);
    char *text = path ? list_dir(path) : NULL;
    KNI_StartHandles(1);
    KNI_DeclareHandle(result);
    if (text != NULL) {
        KNI_NewStringUTF(text, result);
        free(text);
    } else {
        KNI_ReleaseHandle(result);
    }
    free(path);
    KNI_EndHandlesAndReturnObject(result);
}

/* --- Sys.nType(String path) -> int: 0 missing, 1 file, 2 dir, 3 other --- */
KNIEXPORT KNI_RETURNTYPE_INT
KNIDECL(com_sun_midp_appmanager_Sys_nType) {
    char *path = string_arg(1);
    struct stat st;
    int r = 0;
    if (path != NULL && stat(path, &st) == 0) {
        r = S_ISREG(st.st_mode) ? 1 : (S_ISDIR(st.st_mode) ? 2 : 3);
    }
    free(path);
    KNI_ReturnInt(r);
}

/* --- Sys.nSize(String path) -> long (-1 if missing) --------------------- */
KNIEXPORT KNI_RETURNTYPE_LONG
KNIDECL(com_sun_midp_appmanager_Sys_nSize) {
    char *path = string_arg(1);
    long r = path ? file_size(path) : -1;
    free(path);
    KNI_ReturnLong((jlong) r);
}

/* --- Sys.nMtime(String path) -> long seconds ---------------------------- */
KNIEXPORT KNI_RETURNTYPE_LONG
KNIDECL(com_sun_midp_appmanager_Sys_nMtime) {
    char *path = string_arg(1);
    struct stat st;
    long r = 0;
    if (path != NULL && stat(path, &st) == 0) {
        r = (long) st.st_mtime;
    }
    free(path);
    KNI_ReturnLong((jlong) r);
}

/* --- Sys.nMkdir(String path) -> boolean --------------------------------- */
KNIEXPORT KNI_RETURNTYPE_BOOLEAN
KNIDECL(com_sun_midp_appmanager_Sys_nMkdir) {
    char *path = string_arg(1);
    int r = path != NULL && mkdir(path, 0775) == 0;
    free(path);
    KNI_ReturnBoolean(r);
}

/* --- Sys.nRemove(String path) -> boolean (file or empty dir) ------------ */
KNIEXPORT KNI_RETURNTYPE_BOOLEAN
KNIDECL(com_sun_midp_appmanager_Sys_nRemove) {
    char *path = string_arg(1);
    int r = path != NULL && remove_path(path) == 0;
    free(path);
    KNI_ReturnBoolean(r);
}

/* --- Sys.nRename(String from, String to) -> boolean --------------------- */
KNIEXPORT KNI_RETURNTYPE_BOOLEAN
KNIDECL(com_sun_midp_appmanager_Sys_nRename) {
    char *from = string_arg(1);
    char *to = string_arg(2);
    int r = from != NULL && to != NULL && rename(from, to) == 0;
    free(from);
    free(to);
    KNI_ReturnBoolean(r);
}

/* --- Sys.nCopy(String from, String to) -> boolean ----------------------- */
KNIEXPORT KNI_RETURNTYPE_BOOLEAN
KNIDECL(com_sun_midp_appmanager_Sys_nCopy) {
    char *from = string_arg(1);
    char *to = string_arg(2);
    int r = from != NULL && to != NULL && copy_file(from, to) == 0;
    free(from);
    free(to);
    KNI_ReturnBoolean(r);
}

/* --- Sys.nSpace(String path, boolean total) -> long bytes --------------- */
KNIEXPORT KNI_RETURNTYPE_LONG
KNIDECL(com_sun_midp_appmanager_Sys_nSpace) {
    char *path = string_arg(1);
    int total = KNI_GetParameterAsBoolean(2);
    struct statfs sf;
    long long r = -1;
    if (path != NULL && statfs(path, &sf) == 0) {
        r = (long long) sf.f_bsize * (total ? sf.f_blocks : sf.f_bavail);
    }
    free(path);
    KNI_ReturnLong((jlong) r);
}

/* --- Sys.nExec(String cmd, int timeoutMs) -> String output -------------- */
KNIEXPORT KNI_RETURNTYPE_OBJECT
KNIDECL(com_sun_midp_appmanager_Sys_nExec) {
    char *cmd = string_arg(1);
    int timeout = KNI_GetParameterAsInt(2);
    char *out = cmd ? run_capture(cmd, timeout, 65536) : NULL;
    KNI_StartHandles(1);
    KNI_DeclareHandle(result);
    if (out != NULL) {
        KNI_NewStringUTF(out, result);
        free(out);
    } else {
        KNI_ReleaseHandle(result);
    }
    free(cmd);
    KNI_EndHandlesAndReturnObject(result);
}

/* --- Sys.nExitCode() -> int -------------------------------------------- */
KNIEXPORT KNI_RETURNTYPE_INT
KNIDECL(com_sun_midp_appmanager_Sys_nExitCode) {
    KNI_ReturnInt(last_exit_code);
}

/* --- Sys.nSpawn(String cmd) -> int pid ---------------------------------- */
KNIEXPORT KNI_RETURNTYPE_INT
KNIDECL(com_sun_midp_appmanager_Sys_nSpawn) {
    char *cmd = string_arg(1);
    int pid = cmd ? spawn_detached(cmd) : -1;
    free(cmd);
    KNI_ReturnInt(pid);
}

/* --- Sys.nAlive(int pid) -> boolean ------------------------------------- */
KNIEXPORT KNI_RETURNTYPE_BOOLEAN
KNIDECL(com_sun_midp_appmanager_Sys_nAlive) {
    KNI_ReturnBoolean(proc_alive(KNI_GetParameterAsInt(1)));
}

/* --- Sys.nKill(int pid, boolean group) -> boolean ----------------------- */
KNIEXPORT KNI_RETURNTYPE_BOOLEAN
KNIDECL(com_sun_midp_appmanager_Sys_nKill) {
    KNI_ReturnBoolean(proc_kill(KNI_GetParameterAsInt(1), KNI_GetParameterAsBoolean(2)));
}

/* --- Sys.nYuvFrame(path, w, h, fmt, rot, mirror, int[] out, ow, oh) ----- */
KNIEXPORT KNI_RETURNTYPE_INT
KNIDECL(com_sun_midp_appmanager_Sys_nYuvFrame) {
    char *path = string_arg(1);
    int w = KNI_GetParameterAsInt(2);
    int h = KNI_GetParameterAsInt(3);
    int fmt = KNI_GetParameterAsInt(4);
    int rot = KNI_GetParameterAsInt(5);
    int mirror = KNI_GetParameterAsBoolean(6);
    int ow = KNI_GetParameterAsInt(8);
    int oh = KNI_GetParameterAsInt(9);
    int rc = -1;
    yuv_frame fr;
    KNI_StartHandles(1);
    KNI_DeclareHandle(arr);
    KNI_GetParameterAsObject(7, arr);
    if (path != NULL && !KNI_IsNullHandle(arr) && ow > 0 && oh > 0
            && KNI_GetArrayLength(arr) >= ow * oh) {
        rc = yuv_load(path, w, h, fmt, &fr);
        if (rc == 0) {
            int *rgb = (int *) malloc(sizeof(int) * ow * oh);
            if (rgb != NULL) {
                yuv_to_rgb_fit(&fr, rot, mirror, rgb, ow, oh);
                KNI_SetRawArrayRegion(arr, 0, sizeof(int) * ow * oh, (jbyte *) rgb);
                free(rgb);
            } else {
                rc = -3;
            }
            yuv_free(&fr);
        }
    }
    free(path);
    KNI_EndHandles();
    KNI_ReturnInt(rc);
}

/* --- Sys.nYuvJpeg(yuv, w, h, fmt, rot, mirror, div, quality, jpg) -> int  */
KNIEXPORT KNI_RETURNTYPE_INT
KNIDECL(com_sun_midp_appmanager_Sys_nYuvJpeg) {
    char *yuv = string_arg(1);
    int w = KNI_GetParameterAsInt(2);
    int h = KNI_GetParameterAsInt(3);
    int fmt = KNI_GetParameterAsInt(4);
    int rot = KNI_GetParameterAsInt(5);
    int mirror = KNI_GetParameterAsBoolean(6);
    int div = KNI_GetParameterAsInt(7);
    int quality = KNI_GetParameterAsInt(8);
    char *jpg = string_arg(9);
    int rc = -1;
    if (yuv != NULL && jpg != NULL) {
        rc = yuv_file_to_jpeg(yuv, w, h, fmt, rot, mirror, div, quality, jpg);
    }
    free(yuv);
    free(jpg);
    KNI_ReturnInt(rc);
}

/* --- Sys.nRecStart(yuv, w, h, fmt, rot, mirror, avi, fps, div, q, audioRate) */
KNIEXPORT KNI_RETURNTYPE_INT
KNIDECL(com_sun_midp_appmanager_Sys_nRecStart) {
    char *yuv = string_arg(1);
    int w = KNI_GetParameterAsInt(2);
    int h = KNI_GetParameterAsInt(3);
    int fmt = KNI_GetParameterAsInt(4);
    int rot = KNI_GetParameterAsInt(5);
    int mirror = KNI_GetParameterAsBoolean(6);
    char *out = string_arg(7);
    int fps = KNI_GetParameterAsInt(8);
    int div = KNI_GetParameterAsInt(9);
    int quality = KNI_GetParameterAsInt(10);
    int audio = KNI_GetParameterAsInt(11);
    int rc = -1;
    if (yuv != NULL && out != NULL) {
        rc = rec_start(yuv, w, h, fmt, rot, mirror, out, fps, div, quality, audio);
    }
    free(yuv);
    free(out);
    KNI_ReturnInt(rc);
}

/* --- Sys.nRecStop() -> frames written (or -error) ----------------------- */
KNIEXPORT KNI_RETURNTYPE_INT
KNIDECL(com_sun_midp_appmanager_Sys_nRecStop) {
    KNI_ReturnInt(rec_stop());
}

/* --- Sys.nRecFrames() -> int ------------------------------------------- */
KNIEXPORT KNI_RETURNTYPE_INT
KNIDECL(com_sun_midp_appmanager_Sys_nRecFrames) {
    KNI_ReturnInt(rec.running ? rec.frames : (rec.error ? -1 : rec.frames));
}

/*
 * --- Sys.nJpegDecode(String path, int maxW, int maxH, int[] out) -> int
 * Decodes a JPEG file scaled by 1/1, 1/2, 1/4 or 1/8 so that it has at
 * most maxW * maxH pixels, writing 0xRRGGBB pixels; returns (w << 16) | h,
 * or <0.
 */
KNIEXPORT KNI_RETURNTYPE_INT
KNIDECL(com_sun_midp_appmanager_Sys_nJpegDecode) {
    char *path = string_arg(1);
    int maxW = KNI_GetParameterAsInt(2);
    int maxH = KNI_GetParameterAsInt(3);
    int rc = -1;
    KNI_StartHandles(1);
    KNI_DeclareHandle(arr);
    KNI_GetParameterAsObject(4, arr);
#if ENABLE_JPEG
    if (path != NULL && !KNI_IsNullHandle(arr)) {
        long len;
        unsigned char *data = read_file(path, &len);
        if (data != NULL) {
            void *info = JPEG_To_RGB_init();
            if (info != NULL) {
                int w, h;
                if (JPEG_To_RGB_decodeHeader(info, (char *) data, (int) len, &w, &h)) {
                    struct jpeg_decompress_struct *ci = (struct jpeg_decompress_struct *) info;
                    int denom = 1, ow, oh;
                    /* decodeHeader reports output_width/height, which are
                       still 0 before the output size is computed */
                    w = (int) ci->image_width;
                    h = (int) ci->image_height;
                    /* smallest reduction whose pixel count fits the buffer;
                       the viewer pans whatever overhangs one edge */
                    while (denom < 8
                           && (long) (w / denom) * (h / denom) > (long) maxW * maxH) {
                        denom *= 2;
                    }
                    ci->scale_num = 1;
                    ci->scale_denom = denom;
                    jm_jpeg_calc_output_dimensions(ci);
                    ow = (int) ci->output_width;
                    oh = (int) ci->output_height;
                    if (ow > 0 && oh > 0 && KNI_GetArrayLength(arr) >= ow * oh) {
                        unsigned short *px = (unsigned short *) malloc((size_t) ow * oh * 2);
                        int *rgb = (int *) malloc(sizeof(int) * ow * oh);
                        if (px != NULL && rgb != NULL
                                && JPEG_To_RGB_decodeData2(info, (char *) px, 2, 0, 0, ow, oh) != 0) {
                            int i;
                            for (i = 0; i < ow * oh; i++) {
                                unsigned int p = px[i];
                                int r = (p >> 11) & 0x1f, g = (p >> 5) & 0x3f, b = p & 0x1f;
                                rgb[i] = ((r << 3) | (r >> 2)) << 16
                                       | ((g << 2) | (g >> 4)) << 8
                                       | ((b << 3) | (b >> 2));
                            }
                            KNI_SetRawArrayRegion(arr, 0, sizeof(int) * ow * oh, (jbyte *) rgb);
                            rc = (ow << 16) | oh;
                        } else {
                            rc = -3;
                        }
                        free(px);
                        free(rgb);
                    } else {
                        rc = -4;                  /* buffer too small */
                    }
                } else {
                    rc = -2;                      /* not a JPEG */
                }
                JPEG_To_RGB_free(info);
            }
            free(data);
        }
    }
#else
    (void) maxW; (void) maxH;
    rc = -5;                                      /* built without JPEG */
#endif
    free(path);
    KNI_EndHandles();
    KNI_ReturnInt(rc);
}

#endif /* !S100_HOST_TEST */

/* ====================================================================== */
/*                          host self test                                 */
/* ====================================================================== */

#ifdef S100_HOST_TEST
/*
 * gcc -DS100_HOST_TEST -O2 -o s100_test s100_native.c -lm -lpthread
 * ./s100_test out.jpg out.avi
 * Writes a synthetic NV21 frame, converts it to a JPEG and records ten
 * frames of it into an AVI; open both with any viewer to check.
 */
int main(int argc, char **argv) {
    const int W = 640, H = 480;
    unsigned char *frame = (unsigned char *) malloc(W * H * 3 / 2);
    int x, y, rc, i;
    char *ls;
    if (argc < 3) {
        fprintf(stderr, "usage: %s out.jpg out.avi\n", argv[0]);
        return 2;
    }
    /* colour bars with a diagonal gradient, NV21 */
    for (y = 0; y < H; y++) {
        for (x = 0; x < W; x++) {
            frame[y * W + x] = (unsigned char) (16 + ((x + y) * 219) / (W + H));
        }
    }
    for (y = 0; y < H / 2; y++) {
        for (x = 0; x < W / 2; x++) {
            int bar = (x * 8) / (W / 2);
            int U = 128 + ((bar & 1) ? 90 : -90) * ((bar & 4) ? 1 : 0);
            int V = 128 + ((bar & 2) ? 90 : -90);
            frame[W * H + y * W + x * 2] = (unsigned char) V;
            frame[W * H + y * W + x * 2 + 1] = (unsigned char) U;
        }
    }
    write_whole("/tmp/s100_test.yuv", frame, W * H * 3 / 2);
    rc = yuv_file_to_jpeg("/tmp/s100_test.yuv", W, H, 0, 90, 0, 1, 85, argv[1]);
    printf("jpeg rc=%d size=%ld\n", rc, file_size(argv[1]));
    rc = rec_start("/tmp/s100_test.yuv", W, H, 0, 90, 0, argv[2], 10, 2, 70, 0);
    printf("rec start rc=%d\n", rc);
    for (i = 0; i < 10; i++) {
        usleep(100000);
    }
    rc = rec_stop();
    printf("rec stop frames=%d size=%ld\n", rc, file_size(argv[2]));
    {
        int *rgb = (int *) malloc(sizeof(int) * 240 * 258);
        yuv_frame fr;
        if (yuv_load("/tmp/s100_test.yuv", W, H, 0, &fr) == 0) {
            yuv_to_rgb_fit(&fr, 90, 0, rgb, 240, 258);
            printf("rgb[0]=%06x rgb[last]=%06x\n", rgb[0] & 0xffffff, rgb[240 * 258 - 1] & 0xffffff);
            yuv_free(&fr);
        }
        free(rgb);
    }
    ls = list_dir("/tmp");
    printf("list_dir(/tmp): %d bytes\n", ls ? (int) strlen(ls) : -1);
    free(ls);
    {
        char *out = run_capture("echo hello; exit 3", 2000, 1024);
        printf("exec: '%s' code=%d\n", out ? out : "(null)", last_exit_code);
        free(out);
    }
    {
        int pid = spawn_detached("sleep 5");
        printf("spawn pid=%d alive=%d", pid, proc_alive(pid));
        proc_kill(pid, 1);
        printf(" after kill alive=%d\n", proc_alive(pid));
    }
    free(frame);
    return 0;
}
#endif
