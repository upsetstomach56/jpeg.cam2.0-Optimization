cmake_minimum_required(VERSION 3.4.1)

set(ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(ENABLE_STATIC ON CACHE BOOL "" FORCE)
set(ENABLE_EXECUTABLES OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTS OFF CACHE BOOL "" FORCE)
set(WITH_JPEG8 ON CACHE BOOL "" FORCE)

# HARDWARE FIX: Disable SIMD since the BIONZ X lacks the NEON silicon module
set(WITH_SIMD OFF CACHE BOOL "" FORCE)

set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_subdirectory(libjpeg-turbo)
add_library(native-lib SHARED native-lib.cpp)

# SPEED FIX: Optimize the ARMv7 camera build for the BIONZ X Cortex-A5.
# Keep the legacy armeabi build on plain -O3 so the dual-ABI build still works.
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    target_compile_options(native-lib PRIVATE
        -O3
        -ffast-math
        -march=armv7-a
        -mtune=cortex-a5
        -mfpu=vfpv4
        -mfloat-abi=softfp
    )
else()
    target_compile_options(native-lib PRIVATE -O3)
endif()

target_include_directories(native-lib PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/libjpeg-turbo
    ${CMAKE_CURRENT_BINARY_DIR}/libjpeg-turbo
)

set_target_properties(native-lib PROPERTIES LINK_FLAGS "-Wl,-Bsymbolic -Wl,--exclude-libs,ALL")

find_library(log-lib log)
target_link_libraries(native-lib jpeg-static ${log-lib})
