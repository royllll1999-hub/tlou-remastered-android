// SPDX-License-Identifier: GPL-2.0-or-later
#pragma once

// TLOU Android (GOW3_PARK_COPY_WAITS=1, launcher switch): WaitHostCopies slept nowhere: it
// yield-spun until the recording thread had run the guest memory copies, a core busy for
// the whole wait. Bloodborne-Android measured short spin + futex sleep at the same FPS with
// far less CPU (docs/SESSION_2026-10-18_UNIFIED_MEMORY_POWER.md). Here the waiter yields a
// few times, then sleeps on a futex; every store of the completed sequence wakes it when a
// waiter is registered (one relaxed load otherwise). Each sleep is bounded (1 ms), so a
// missed wake costs at most that, never a hang.

#include <atomic>
#include <cstdint>
#include <cstdlib>
#include <thread>
#ifdef __linux__
#include <linux/futex.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>
#endif

namespace Vulkan::Detail {

inline bool ParkCopyWaitsEnabled() {
    static const bool enabled = [] {
        const char* env = std::getenv("GOW3_PARK_COPY_WAITS");
        return env && env[0] == '1' && env[1] == '\0';
    }();
    return enabled;
}

/// Futex word bumped on every completion store while waiters exist.
struct SeqParkSignal {
    std::atomic<std::uint32_t> word{0};
    std::atomic<std::uint32_t> waiters{0};

    void Notify() {
        if (waiters.load(std::memory_order_seq_cst) == 0) {
            return;
        }
        word.fetch_add(1, std::memory_order_seq_cst);
#ifdef __linux__
        syscall(SYS_futex, reinterpret_cast<std::uint32_t*>(&word), FUTEX_WAKE_PRIVATE, INT32_MAX,
                nullptr, nullptr, 0);
#endif
    }
};

/// Waits until `completed` reaches `target`; `kick` runs before every sleep.
template <class Kick>
void ParkUntilSequence(const std::atomic<std::uint64_t>& completed, std::uint64_t target,
                       SeqParkSignal& signal, Kick&& kick) {
    for (unsigned spins = 0; spins < 32; ++spins) {
        if (completed.load(std::memory_order_acquire) >= target) {
            return;
        }
        std::this_thread::yield();
    }
    signal.waiters.fetch_add(1, std::memory_order_seq_cst);
    while (true) {
        const std::uint32_t seen = signal.word.load(std::memory_order_seq_cst);
        if (completed.load(std::memory_order_acquire) >= target) {
            break;
        }
        kick();
#ifdef __linux__
        timespec timeout{0, 1'000'000};
        syscall(SYS_futex, reinterpret_cast<std::uint32_t*>(&signal.word), FUTEX_WAIT_PRIVATE,
                seen, &timeout, nullptr, 0);
#else
        std::this_thread::yield();
#endif
    }
    signal.waiters.fetch_sub(1, std::memory_order_seq_cst);
}

} // namespace Vulkan::Detail
