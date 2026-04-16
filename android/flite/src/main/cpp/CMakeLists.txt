cmake_minimum_required(VERSION 3.22.1)
project(flite_jni)

set(FLITE_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../flite-master")

file(GLOB_RECURSE FLITE_CORE_SOURCES
    "${FLITE_ROOT}/src/*.c"
)
list(FILTER FLITE_CORE_SOURCES EXCLUDE REGEX ".*/src/audio/.*\\.c$")
list(FILTER FLITE_CORE_SOURCES EXCLUDE REGEX ".*/src/utils/cst_socket\\.c$")
list(FILTER FLITE_CORE_SOURCES EXCLUDE REGEX ".*/src/utils/cst_file_wince\\.c$")
list(FILTER FLITE_CORE_SOURCES EXCLUDE REGEX ".*/src/utils/cst_file_palmos\\.c$")
list(FILTER FLITE_CORE_SOURCES EXCLUDE REGEX ".*/src/utils/cst_mmap_win32\\.c$")
list(FILTER FLITE_CORE_SOURCES EXCLUDE REGEX ".*/src/utils/cst_mmap_none\\.c$")
file(GLOB_RECURSE FLITE_LEX_SOURCES
    "${FLITE_ROOT}/lang/cmulex/*.c"
)
list(FILTER FLITE_LEX_SOURCES EXCLUDE REGEX ".*/lang/cmulex/cmu_lex_.*(raw|table|bytes)\\.c$")
file(GLOB_RECURSE FLITE_USEN_SOURCES
    "${FLITE_ROOT}/lang/usenglish/*.c"
)
file(GLOB_RECURSE FLITE_KAL_SOURCES
    "${FLITE_ROOT}/lang/cmu_us_kal/*.c"
)
file(GLOB_RECURSE FLITE_KAL16_SOURCES
    "${FLITE_ROOT}/lang/cmu_us_kal16/*.c"
)
file(GLOB_RECURSE FLITE_AWB_SOURCES
    "${FLITE_ROOT}/lang/cmu_us_awb/*.c"
)
file(GLOB_RECURSE FLITE_RMS_SOURCES
    "${FLITE_ROOT}/lang/cmu_us_rms/*.c"
)
file(GLOB_RECURSE FLITE_SLT_SOURCES
    "${FLITE_ROOT}/lang/cmu_us_slt/*.c"
)

add_library(
    flite_jni
    SHARED
    flite_jni.cpp
    "${FLITE_ROOT}/src/audio/audio.c"
    "${FLITE_ROOT}/src/audio/au_none.c"
    "${FLITE_ROOT}/src/audio/au_streaming.c"
    ${FLITE_CORE_SOURCES}
    ${FLITE_LEX_SOURCES}
    ${FLITE_USEN_SOURCES}
    ${FLITE_KAL_SOURCES}
    ${FLITE_KAL16_SOURCES}
    ${FLITE_AWB_SOURCES}
    ${FLITE_RMS_SOURCES}
    ${FLITE_SLT_SOURCES}
)

target_include_directories(flite_jni PRIVATE
    "${FLITE_ROOT}/include"
    "${FLITE_ROOT}/lang/cmulex"
    "${FLITE_ROOT}/lang/usenglish"
    "${FLITE_ROOT}/lang/cmu_us_kal"
    "${FLITE_ROOT}/lang/cmu_us_kal16"
    "${FLITE_ROOT}/lang/cmu_us_awb"
    "${FLITE_ROOT}/lang/cmu_us_rms"
    "${FLITE_ROOT}/lang/cmu_us_slt"
    "${FLITE_ROOT}/src/audio"
    "${FLITE_ROOT}/src/hrg"
    "${FLITE_ROOT}/src/regex"
    "${FLITE_ROOT}/src/speech"
    "${FLITE_ROOT}/src/stats"
    "${FLITE_ROOT}/src/synth"
    "${FLITE_ROOT}/src/utils"
    "${FLITE_ROOT}/src/wavesynth"
)

target_compile_definitions(flite_jni PRIVATE
    CST_NO_SOCKETS=1
    FLITE_PLUS_HTS_ENGINE=0
)

find_library(log-lib log)

target_link_libraries(flite_jni ${log-lib})
