cmake_minimum_required(VERSION 3.8)

project(espeak-android)

get_filename_component(_repo_root "${CMAKE_CURRENT_LIST_DIR}/../../.." ABSOLUTE)
set(_espeak_root "${_repo_root}/UniApp/third_party/espeak-ng")
set(_upstream_jni "${_espeak_root}/android/jni")

if(NOT EXISTS "${_upstream_jni}/CMakeLists.txt")
    message(FATAL_ERROR "eSpeak NG jni not found at: ${_upstream_jni}")
endif()

add_subdirectory("${_upstream_jni}" espeakng_upstream)
