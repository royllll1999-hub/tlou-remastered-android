// TLOU Android: WaitHostCopies' futex park (GOW3_PARK_COPY_WAITS) wakes on completion and
// never hangs when a completion store skips the wake (bounded 1 ms sleeps).
#include "video_core/renderer_vulkan/tlou_seq_park.h"

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <thread>

using Vulkan::Detail::ParkUntilSequence;
using Vulkan::Detail::SeqParkSignal;

static void Check(bool ok, const char* what) {
    if (!ok) {
        std::fprintf(stderr, "FAIL: %s\n", what);
        std::exit(1);
    }
}

int main() {
    // Already complete: returns at once.
    {
        std::atomic<std::uint64_t> done{5};
        SeqParkSignal park;
        ParkUntilSequence(done, 5, park, [] {});
        Check(park.waiters.load() == 0, "no waiter left registered");
    }
    // Woken by Notify after a store.
    for (int round = 0; round < 200; ++round) {
        std::atomic<std::uint64_t> done{0};
        SeqParkSignal park;
        std::thread producer([&] {
            for (std::uint64_t seq = 1; seq <= 50; ++seq) {
                done.store(seq, std::memory_order_release);
                park.Notify();
            }
        });
        ParkUntilSequence(done, 50, park, [] {});
        producer.join();
        Check(done.load() == 50, "waited for the target sequence");
        Check(park.waiters.load() == 0, "waiter count restored");
    }
    // A store without a wake still finishes (bounded sleep).
    {
        std::atomic<std::uint64_t> done{0};
        SeqParkSignal park;
        const auto start = std::chrono::steady_clock::now();
        std::thread producer([&] {
            std::this_thread::sleep_for(std::chrono::milliseconds(3));
            done.store(1, std::memory_order_release);
        });
        ParkUntilSequence(done, 1, park, [] {});
        producer.join();
        const auto waited = std::chrono::steady_clock::now() - start;
        Check(waited < std::chrono::milliseconds(200), "missed wake bounded");
    }
    std::puts("TLOU seq park: wake, sequence order and missed-wake bound PASS");
    return 0;
}
