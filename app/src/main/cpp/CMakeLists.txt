cmake_minimum_required(VERSION 3.22.1)
project(gearcam_audio LANGUAGES C CXX)

set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_CXXLIBS OFF CACHE BOOL "" FORCE)
set(BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_DOCS OFF CACHE BOOL "" FORCE)
set(INSTALL_MANPAGES OFF CACHE BOOL "" FORCE)
set(WITH_OGG OFF CACHE BOOL "" FORCE)
set(INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(ENABLE_MULTITHREADING OFF CACHE BOOL "" FORCE)
add_subdirectory(vendor/flac EXCLUDE_FROM_ALL)
target_compile_options(FLAC PRIVATE -O2)

set(LAME_DIR ${CMAKE_CURRENT_SOURCE_DIR}/vendor/lame)
file(GLOB LAME_SOURCES CONFIGURE_DEPENDS ${LAME_DIR}/libmp3lame/*.c)
add_library(gearcam_lame STATIC ${LAME_SOURCES})
target_include_directories(gearcam_lame PUBLIC ${LAME_DIR}/include PRIVATE ${LAME_DIR}/libmp3lame)
target_compile_definitions(gearcam_lame PRIVATE STDC_HEADERS HAVE_STDINT_H HAVE_INTTYPES_H HAVE_ERRNO_H HAVE_FCNTL_H HAVE_LIMITS_H ieee754_float32_t=float ieee754_float64_t=double)
target_compile_options(gearcam_lame PRIVATE -O2)
target_link_libraries(gearcam_lame PRIVATE m)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LIBUSB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/vendor/libusb/libusb)
add_library(gearcam_libusb STATIC
        ${LIBUSB_DIR}/core.c
        ${LIBUSB_DIR}/descriptor.c
        ${LIBUSB_DIR}/hotplug.c
        ${LIBUSB_DIR}/io.c
        ${LIBUSB_DIR}/strerror.c
        ${LIBUSB_DIR}/sync.c
        ${LIBUSB_DIR}/os/events_posix.c
        ${LIBUSB_DIR}/os/linux_netlink.c
        ${LIBUSB_DIR}/os/linux_usbfs.c
        ${LIBUSB_DIR}/os/threads_posix.c)
target_include_directories(gearcam_libusb PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${LIBUSB_DIR}
        ${LIBUSB_DIR}/os)
target_compile_options(gearcam_libusb PRIVATE -fvisibility=hidden)
target_link_libraries(gearcam_libusb PRIVATE log)

set(LIBUAC_DIR ${CMAKE_CURRENT_SOURCE_DIR}/vendor/libuac)
add_library(gearcam_libuac STATIC
        ${LIBUAC_DIR}/uac_context.cpp
        ${LIBUAC_DIR}/uac_device.cpp
        ${LIBUAC_DIR}/uac_exceptions.cpp
        ${LIBUAC_DIR}/uac_parser.cpp
        ${LIBUAC_DIR}/uac_streaming.cpp)
target_include_directories(gearcam_libuac PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${LIBUAC_DIR}
        ${LIBUSB_DIR})
target_compile_options(gearcam_libuac PRIVATE -fvisibility=hidden)
target_link_libraries(gearcam_libuac PRIVATE gearcam_libusb log)

add_library(gearcam_audio SHARED usb_audio_jni.cpp audio_dsp_jni.cpp audio_encoder_jni.cpp)
target_include_directories(gearcam_audio PRIVATE ${LIBUAC_DIR} ${LIBUSB_DIR})
# Real-time audio DSP: optimize even in debug builds, where -O0 would be the default. No FMA
# contraction, so results match the Java fallback loops bit for bit.
target_compile_options(gearcam_audio PRIVATE -Wall -Wextra -O2 -ffp-contract=off)
target_link_libraries(gearcam_audio PRIVATE gearcam_libuac gearcam_libusb gearcam_lame FLAC log)
