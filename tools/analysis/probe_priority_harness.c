/* Exercise playback probe preference and cancellation without Android hardware. */
#include "airmedy_analysis_bridge.h"
#include "ffmpeg_shared_mu.h"
#include "airmedy_analyzer.h"
#include <assert.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <unistd.h>

typedef struct {
    SistrumAnalysis *state;
    atomic_int done;
    int status;
} Run;

static void *analyze(void *value) {
    Run *run = value;
    double output[12];
    run->status = sistrum_analysis_run(run->state, output);
    atomic_store(&run->done, 1);
    return NULL;
}

static void check_waiter_and_cancellation(const char *path, int cancel) {
    Run run = {0};
    atomic_init(&run.done, 0);
    run.state = sistrum_analysis_create();
    assert(run.state);
    assert(sistrum_analysis_open(run.state, open(path, O_RDONLY)) == 0);

    pthread_mutex_lock(&ffmpeg_probe_mu);
    sistrum_playback_probe_enter();
    pthread_t thread;
    assert(pthread_create(&thread, NULL, analyze, &run) == 0);
    usleep(100000);
    pthread_mutex_unlock(&ffmpeg_probe_mu);
    usleep(100000);
    assert(!atomic_load(&run.done));
    if (cancel) sistrum_analysis_cancel(run.state);
    sistrum_playback_probe_leave();
    assert(pthread_join(thread, NULL) == 0);
    if (cancel) assert(run.status != 0);
    else assert(run.status == 0);
    assert(pthread_mutex_trylock(&ffmpeg_probe_mu) == 0);
    pthread_mutex_unlock(&ffmpeg_probe_mu);
    sistrum_analysis_destroy(run.state);
}

int main(int argc, char **argv) {
    if (argc != 2) return 2;
    atomic_int cancelled = 0;
    FFAInterrupt probe = { &cancelled, av_gettime_relative() + 1000000 };
    assert(!ffa_interrupt(&probe));
    sistrum_playback_probe_enter();
    assert(ffa_interrupt(&probe));
    probe.deadline_us = 0;
    assert(!ffa_interrupt(&probe));
    sistrum_playback_probe_leave();
    atomic_store(&cancelled, 1);
    assert(ffa_interrupt(&probe));
    check_waiter_and_cancellation(argv[1], 1);
    check_waiter_and_cancellation(argv[1], 0);
    puts("PASS: playback waiter, cancellation, retry and probe lock cleanup");
    return 0;
}
