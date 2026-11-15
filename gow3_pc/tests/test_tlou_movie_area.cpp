// The movie area filter (gpu/shim/tlou_movie_area.h) against an exact box-filter reference:
// 1080p 4:2:0 into 720p (3:2) and 540p (2:1) NV12, luma and interleaved chroma.
#include "../gpu/shim/tlou_movie_area.h"
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <vector>

static double Reference(const std::vector<unsigned char>& p, unsigned pw, double s, unsigned ox,
                        unsigned oy) {
    const double x0 = ox * s, x1 = x0 + s, y0 = oy * s, y1 = y0 + s;
    double acc = 0;
    for (int yy = int(y0); yy < int(std::ceil(y1)); ++yy)
        for (int xx = int(x0); xx < int(std::ceil(x1)); ++xx)
            acc += (std::fmin(xx + 1, x1) - std::fmax(xx, x0)) *
                   (std::fmin(yy + 1, y1) - std::fmax(yy, y0)) * p[std::size_t(yy) * pw + xx];
    return acc / (s * s);
}

int main() {
    for (unsigned x = 0; x <= 9 * 255; ++x)
        if (TlouMovieArea::Div9(x) != unsigned(std::lround(x / 9.0))) {
            std::fprintf(stderr, "Div9(%u) wrong\n", x);
            return 1;
        }
    const unsigned W = 1920, H = 1080;
    std::vector<unsigned char> y(W * H), u(W * H / 4), v(W * H / 4);
    std::srand(7);
    for (auto& b : y) b = (unsigned char)std::rand();
    for (auto& b : u) b = (unsigned char)std::rand();
    for (auto& b : v) b = (unsigned char)std::rand();
    for (const unsigned w : {1280u, 960u}) {
        const unsigned h = w == 1280 ? 720 : 540, pitch = (w + 63) & ~63u;
        const double s = double(W) / w;
        for (const bool scalar : {true, false}) {
            std::vector<unsigned char> luma(pitch * h), chroma(pitch * h / 2);
            if (!TlouMovieArea::Downscale(y.data(), u.data(), v.data(), W, W / 2, W / 2, W, H,
                                          luma.data(), chroma.data(), pitch, pitch, w, h, scalar)) {
                std::fprintf(stderr, "%ux%u not handled\n", w, h);
                return 1;
            }
            for (unsigned r = 0; r < h; ++r)
                for (unsigned c = 0; c < w; ++c)
                    if (luma[r * pitch + c] != std::lround(Reference(y, W, s, c, r))) {
                        std::fprintf(stderr, "luma %ux%u at %u,%u\n", w, h, c, r);
                        return 1;
                    }
            for (unsigned r = 0; r < h / 2; ++r)
                for (unsigned c = 0; c < w / 2; ++c)
                    if (chroma[r * pitch + 2 * c] != std::lround(Reference(u, W / 2, s, c, r)) ||
                        chroma[r * pitch + 2 * c + 1] != std::lround(Reference(v, W / 2, s, c, r))) {
                        std::fprintf(stderr, "chroma %ux%u at %u,%u\n", w, h, c, r);
                        return 1;
                    }
        }
    }
    if (TlouMovieArea::Ratio(1920, 1080, 1600, 900) || TlouMovieArea::Ratio(1920, 1080, 1920, 1080)) {
        std::fprintf(stderr, "unsupported sizes must use swscale\n");
        return 1;
    }
    std::puts("tlou movie area filter: exact");
    return 0;
}
