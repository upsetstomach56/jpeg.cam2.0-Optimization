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

# SPEED FIX: Optimize for the BIONZ X Cortex-A5 in-order pipeline.
# -mtune=cortex-a5: instruction scheduling for the A5's pipeline
# -mfpu=vfpv4: use VFPv4 float unit (confirmed by cpuinfo, no NEON)
# -mfloat-abi=softfp: ABI compatible with Android NDK
target_compile_options(native-lib PRIVATE
    -O3
    -ffast-math
    -march=armv7-a
    -mtune=cortex-a5
    -mfpu=vfpv4
    -mfloat-abi=softfp
)

target_include_directories(native-lib PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/libjpeg-turbo
    ${CMAKE_CURRENT_BINARY_DIR}/libjpeg-turbo
)

set_target_properties(native-lib PROPERTIES LINK_FLAGS "-Wl,-Bsymbolic -Wl,--exclude-libs,ALL")

find_library(log-lib log)
target_link_libraries(native-lib jpeg-static ${log-lib})
