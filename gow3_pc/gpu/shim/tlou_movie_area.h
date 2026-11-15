// SPDX-License-Identifier: GPL-2.0-or-later
#pragma once
// TLOU Android: a decoded 4:2:0 movie picture (separate Y, U, V planes, as FFmpeg's H.264
// decoder gives it) made smaller by an exact 3:2 or 2:1 straight into an NV12 frame, with an
// area filter. Ported from the Uncharted Android port (patch 0084, videodec2_impl.cpp
// AreaDownscale): there sws_scale took 8 ms of the 16.7 a 60 FPS movie frame has for 720p.
// Here it also replaces the full-size YUV420P -> NV12 conversion that ran before the scale,
// so a 1080p movie shown at 720p is read once instead of three times.
// Returns false for another ratio or size; the caller keeps the swscale path then.
#include <cstdint>
#if defined(__aarch64__)
#include <arm_neon.h>
#endif

namespace TlouMovieArea {

// 0 (unsupported), 32 (3:2) or 21 (2:1) for a source w x h shown at width x height.
inline int Ratio(std::uint32_t w, std::uint32_t h, std::uint32_t width, std::uint32_t height) {
    if (width * 3 == w * 2 && height * 3 == h * 2 && w % 96 == 0 && h % 6 == 0) return 32;
    if (width * 2 == w && height * 2 == h && w % 64 == 0 && h % 4 == 0) return 21;
    return 0;
}

// x / 9 rounded, x <= 9 * 255 (the NEON path computes the same with a doubling multiply).
inline std::uint8_t Div9(std::uint32_t x) {
    return static_cast<std::uint8_t>((x * 7282u + 32768u) >> 16);
}

// Scalar reference: one 3x3 block (rows r0..r2, columns x..x+2) into a 2x2 block.
// An output pixel covers one source pixel and half of the next on each axis (weights 2 and 1).
inline void Block32(const std::uint8_t* r0, const std::uint8_t* r1, const std::uint8_t* r2,
                    std::uint32_t x, std::uint8_t out[4]) {
    const std::uint32_t a = r0[x], b = r0[x + 1], c = r0[x + 2];
    const std::uint32_t d = r1[x], e = r1[x + 1], f = r1[x + 2];
    const std::uint32_t g = r2[x], h = r2[x + 1], i = r2[x + 2];
    out[0] = Div9(4 * a + 2 * b + 2 * d + e);
    out[1] = Div9(4 * c + 2 * b + 2 * f + e);
    out[2] = Div9(4 * g + 2 * h + 2 * d + e);
    out[3] = Div9(4 * i + 2 * h + 2 * f + e);
}

#if defined(__aarch64__)
struct Wide {
    uint16x8_t lo, hi;
};
inline Wide Weigh21(uint8x16_t major, uint8x16_t minor) {
    return {vaddw_u8(vshll_n_u8(vget_low_u8(major), 1), vget_low_u8(minor)),
            vaddw_high_u8(vshll_high_n_u8(major, 1), minor)};
}
inline Wide Weigh21(Wide major, Wide minor) {
    return {vaddq_u16(vshlq_n_u16(major.lo, 1), minor.lo),
            vaddq_u16(vshlq_n_u16(major.hi, 1), minor.hi)};
}
inline uint8x16_t Div9(Wide x) {
    return vcombine_u8(vqmovun_s16(vqrdmulhq_n_s16(vreinterpretq_s16_u16(x.lo), 3641)),
                       vqmovun_s16(vqrdmulhq_n_s16(vreinterpretq_s16_u16(x.hi), 3641)));
}
// 16 groups of 3 pixels in 3 rows -> 2 rows of 32 pixels.
inline void Area32(const std::uint8_t* r0, const std::uint8_t* r1, const std::uint8_t* r2,
                   uint8x16x2_t& top, uint8x16x2_t& bottom) {
    const uint8x16x3_t a = vld3q_u8(r0), b = vld3q_u8(r1), c = vld3q_u8(r2);
    Wide up[3], down[3];
    for (int k = 0; k < 3; ++k) {
        up[k] = Weigh21(a.val[k], b.val[k]);
        down[k] = Weigh21(c.val[k], b.val[k]);
    }
    top.val[0] = Div9(Weigh21(up[0], up[1]));
    top.val[1] = Div9(Weigh21(up[2], up[1]));
    bottom.val[0] = Div9(Weigh21(down[0], down[1]));
    bottom.val[1] = Div9(Weigh21(down[2], down[1]));
}
// 16 pairs in 2 rows: the mean of each 2x2.
inline uint8x16_t Area21(const std::uint8_t* r0, const std::uint8_t* r1) {
    const uint8x16x2_t a = vld2q_u8(r0), b = vld2q_u8(r1);
    const uint16x8_t lo = vaddq_u16(vaddl_u8(vget_low_u8(a.val[0]), vget_low_u8(a.val[1])),
                                    vaddl_u8(vget_low_u8(b.val[0]), vget_low_u8(b.val[1])));
    const uint16x8_t hi =
        vaddq_u16(vaddl_high_u8(a.val[0], a.val[1]), vaddl_high_u8(b.val[0], b.val[1]));
    return vcombine_u8(vrshrn_n_u16(lo, 2), vrshrn_n_u16(hi, 2));
}
#endif

// src planes (w x h luma, w/2 x h/2 chroma) into luma (pitch ls) and interleaved UV (pitch cs).
// force_scalar is for tests.
inline bool Downscale(const std::uint8_t* y_plane, const std::uint8_t* u_plane,
                      const std::uint8_t* v_plane, int ys, int us, int vs, std::uint32_t w,
                      std::uint32_t h, std::uint8_t* luma, std::uint8_t* chroma, std::uint32_t ls,
                      std::uint32_t cs, std::uint32_t width, std::uint32_t height,
                      bool force_scalar = false) {
    const int ratio = Ratio(w, h, width, height);
    if (!ratio || ls < width || cs < width) return false;
#if defined(__aarch64__)
    if (!force_scalar) {
        if (ratio == 32) {
            for (std::uint32_t y = 0; y < h; y += 3) {
                const std::uint8_t* r = y_plane + std::int64_t(y) * ys;
                std::uint8_t* d = luma + std::uint64_t(y / 3 * 2) * ls;
                for (std::uint32_t x = 0; x < w; x += 48) {
                    uint8x16x2_t top, bottom;
                    Area32(r + x, r + ys + x, r + 2 * ys + x, top, bottom);
                    vst2q_u8(d + x / 3 * 2, top);
                    vst2q_u8(d + ls + x / 3 * 2, bottom);
                }
            }
            for (std::uint32_t y = 0; y < h / 2; y += 3) {
                const std::uint8_t* u = u_plane + std::int64_t(y) * us;
                const std::uint8_t* v = v_plane + std::int64_t(y) * vs;
                std::uint8_t* d = chroma + std::uint64_t(y / 3 * 2) * cs;
                for (std::uint32_t x = 0; x < w / 2; x += 48) {
                    uint8x16x2_t ut, ub, vt, vb;
                    Area32(u + x, u + us + x, u + 2 * us + x, ut, ub);
                    Area32(v + x, v + vs + x, v + 2 * vs + x, vt, vb);
                    vst4q_u8(d + x / 3 * 4,
                             (uint8x16x4_t{{ut.val[0], vt.val[0], ut.val[1], vt.val[1]}}));
                    vst4q_u8(d + cs + x / 3 * 4,
                             (uint8x16x4_t{{ub.val[0], vb.val[0], ub.val[1], vb.val[1]}}));
                }
            }
        } else {
            for (std::uint32_t y = 0; y < h; y += 2) {
                const std::uint8_t* r = y_plane + std::int64_t(y) * ys;
                std::uint8_t* d = luma + std::uint64_t(y / 2) * ls;
                for (std::uint32_t x = 0; x < w; x += 32) vst1q_u8(d + x / 2, Area21(r + x, r + ys + x));
            }
            for (std::uint32_t y = 0; y < h / 2; y += 2) {
                const std::uint8_t* u = u_plane + std::int64_t(y) * us;
                const std::uint8_t* v = v_plane + std::int64_t(y) * vs;
                std::uint8_t* d = chroma + std::uint64_t(y / 2) * cs;
                for (std::uint32_t x = 0; x < w / 2; x += 32)
                    vst2q_u8(d + x, (uint8x16x2_t{{Area21(u + x, u + us + x), Area21(v + x, v + vs + x)}}));
            }
        }
        return true;
    }
#endif
    (void)force_scalar;
    if (ratio == 32) {
        std::uint8_t o[4], p[4];
        for (std::uint32_t y = 0; y < h; y += 3) {
            const std::uint8_t* r = y_plane + std::int64_t(y) * ys;
            std::uint8_t* d = luma + std::uint64_t(y / 3 * 2) * ls;
            for (std::uint32_t x = 0; x < w; x += 3) {
                Block32(r, r + ys, r + 2 * ys, x, o);
                const std::uint32_t c = x / 3 * 2;
                d[c] = o[0]; d[c + 1] = o[1]; d[ls + c] = o[2]; d[ls + c + 1] = o[3];
            }
        }
        for (std::uint32_t y = 0; y < h / 2; y += 3) {
            const std::uint8_t* u = u_plane + std::int64_t(y) * us;
            const std::uint8_t* v = v_plane + std::int64_t(y) * vs;
            std::uint8_t* d = chroma + std::uint64_t(y / 3 * 2) * cs;
            for (std::uint32_t x = 0; x < w / 2; x += 3) {
                Block32(u, u + us, u + 2 * us, x, o);
                Block32(v, v + vs, v + 2 * vs, x, p);
                const std::uint32_t c = x / 3 * 4;
                d[c] = o[0]; d[c + 1] = p[0]; d[c + 2] = o[1]; d[c + 3] = p[1];
                d[cs + c] = o[2]; d[cs + c + 1] = p[2]; d[cs + c + 2] = o[3]; d[cs + c + 3] = p[3];
            }
        }
    } else {
        auto mean = [](const std::uint8_t* a, const std::uint8_t* b, std::uint32_t x) {
            return static_cast<std::uint8_t>((a[x] + a[x + 1] + b[x] + b[x + 1] + 2u) >> 2);
        };
        for (std::uint32_t y = 0; y < h; y += 2) {
            const std::uint8_t* r = y_plane + std::int64_t(y) * ys;
            std::uint8_t* d = luma + std::uint64_t(y / 2) * ls;
            for (std::uint32_t x = 0; x < w; x += 2) d[x / 2] = mean(r, r + ys, x);
        }
        for (std::uint32_t y = 0; y < h / 2; y += 2) {
            const std::uint8_t* u = u_plane + std::int64_t(y) * us;
            const std::uint8_t* v = v_plane + std::int64_t(y) * vs;
            std::uint8_t* d = chroma + std::uint64_t(y / 2) * cs;
            for (std::uint32_t x = 0; x < w / 2; x += 2) {
                d[x] = mean(u, u + us, x);
                d[x + 1] = mean(v, v + vs, x);
            }
        }
    }
    return true;
}

} // namespace TlouMovieArea
