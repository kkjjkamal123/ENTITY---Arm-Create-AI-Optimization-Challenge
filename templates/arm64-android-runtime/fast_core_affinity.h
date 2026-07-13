#pragma once

#include <algorithm>
#include <cstdio>
#include <sched.h>
#include <unistd.h>
#include <utility>
#include <vector>

// Copyable Linux/Android affinity helper derived from ENTITY's native path.
// It ranks cores by cpuinfo_max_freq, avoiding assumptions such as "big cores are 4-7".
namespace entity_runtime {

struct FastCoreSet {
    cpu_set_t mask;
    int count = 0;
};

inline std::vector<int> rank_online_cpus_by_max_frequency() {
    const long online = sysconf(_SC_NPROCESSORS_ONLN);
    std::vector<std::pair<long, int>> cores;
    for (int cpu = 0; cpu < online; ++cpu) {
        char path[128];
        std::snprintf(path, sizeof(path),
                      "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu);
        long khz = 0;
        if (FILE *file = std::fopen(path, "r")) {
            if (std::fscanf(file, "%ld", &khz) != 1) khz = 0;
            std::fclose(file);
        }
        cores.emplace_back(khz, cpu);
    }
    std::sort(cores.begin(), cores.end(), [](const auto &left, const auto &right) {
        return left.first == right.first ? left.second < right.second : left.first > right.first;
    });

    std::vector<int> ranked;
    ranked.reserve(cores.size());
    for (const auto &[_, cpu] : cores) ranked.push_back(cpu);
    return ranked;
}

inline FastCoreSet fastest_core_set(int requestedCores) {
    FastCoreSet result;
    CPU_ZERO(&result.mask);
    if (requestedCores <= 0) return result;

    for (int cpu : rank_online_cpus_by_max_frequency()) {
        if (result.count == requestedCores) break;
        CPU_SET(cpu, &result.mask);
        ++result.count;
    }
    return result;
}

inline bool pin_current_thread(const FastCoreSet &cores) {
    return cores.count > 0 && sched_setaffinity(0, sizeof(cpu_set_t), &cores.mask) == 0;
}

}  // namespace entity_runtime
