#include <math.h>
#include <stdio.h>
#include "../../androidApp/src/main/cpp/third_party/libebur128/ebur128.h"
int main(void) {
    ebur128_state *s = ebur128_init(2, 48000, EBUR128_MODE_I | EBUR128_MODE_LRA | EBUR128_MODE_TRUE_PEAK | EBUR128_MODE_HISTOGRAM);
    if (!s) return 1;
    float buf[8192];
    size_t n;
    while ((n = fread(buf, sizeof(float), 8192, stdin)) > 0) {
        if (n % 2 || ebur128_add_frames_float(s, buf, n / 2) != EBUR128_SUCCESS) return 2;
    }
    double i, lra, l, r;
    if (ebur128_loudness_global(s,&i) || ebur128_loudness_range(s,&lra) ||
        ebur128_true_peak(s,0,&l) || ebur128_true_peak(s,1,&r)) return 3;
    double peak=fmax(l,r);
    printf("%.6f %.6f %.6f\n",i,peak>0 ? 20*log10(peak) : -INFINITY,lra);
    ebur128_destroy(&s);
}
