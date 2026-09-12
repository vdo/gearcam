cmake_minimum_required(VERSION 3.22.1)
project(gearcam_audio LANGUAGES C CXX)

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

add_library(gearcam_audio SHARED usb_audio_jni.cpp audio_dsp_jni.cpp)
target_include_directories(gearcam_audio PRIVATE ${LIBUAC_DIR} ${LIBUSB_DIR})
# Real-time audio DSP: optimize even in debug builds, where -O0 would be the default. No FMA
# contraction, so results match the Java fallback loops bit for bit.
target_compile_options(gearcam_audio PRIVATE -Wall -Wextra -O2 -ffp-contract=off)
target_link_libraries(gearcam_audio PRIVATE gearcam_libuac gearcam_libusb log)

