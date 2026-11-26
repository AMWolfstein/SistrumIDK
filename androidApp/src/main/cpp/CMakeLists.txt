cmake_minimum_required(VERSION 3.22.1)
project(sistrum_player LANGUAGES C CXX)

set(FFMPEG_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")
set(FFMPEG_INCLUDE "${CMAKE_CURRENT_SOURCE_DIR}/../../../build/ffmpeg/include")

if(NOT EXISTS "${FFMPEG_INCLUDE}/libavcodec/avcodec.h" OR
   NOT EXISTS "${FFMPEG_INCLUDE}/libavfilter/avfilter.h" OR
   NOT EXISTS "${FFMPEG_ROOT}/libavfilter.so")
    message(FATAL_ERROR "FFmpeg artifacts are missing. Run: bash scripts/build-ffmpeg-android.sh ${ANDROID_ABI}")
endif()

foreach(library avutil swresample avcodec avformat avfilter)
    add_library(${library} SHARED IMPORTED)
    set_target_properties(${library} PROPERTIES IMPORTED_LOCATION "${FFMPEG_ROOT}/lib${library}.so")
endforeach()

add_library(sistrum_player SHARED
    ffmpeg_player.cpp
    offline_analysis_decoder.cpp
    airmedy_analysis_bridge.c
    ffmpeg_shared_mu.c
)
set(ANALYSIS_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../build/analysis")
if(NOT EXISTS "${ANALYSIS_ROOT}/lib/libaubio.a" OR NOT EXISTS "${ANALYSIS_ROOT}/lib/libfftw3f.a")
    message(FATAL_ERROR "Analysis artifacts are missing. Run: bash scripts/build-analysis-deps-android.sh")
endif()
target_include_directories(sistrum_player PRIVATE "${FFMPEG_INCLUDE}" "${ANALYSIS_ROOT}/include")
target_compile_options(sistrum_player PRIVATE -Oz -ffunction-sections -fdata-sections)
target_link_options(sistrum_player PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
target_link_libraries(sistrum_player avfilter avformat avcodec swresample avutil "${ANALYSIS_ROOT}/lib/libaubio.a" "${ANALYSIS_ROOT}/lib/libfftw3f.a" aaudio android log m)
