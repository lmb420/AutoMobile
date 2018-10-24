# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html

cmake_minimum_required(VERSION 3.4.1)

set(CMAKE_VERBOSE_MAKEFILE on)

get_filename_component(AM_SRC_ROOT ${CMAKE_SOURCE_DIR}/../../../..  ABSOLUTE)
get_filename_component(SAMPLE_SRC_DIR  ${CMAKE_SOURCE_DIR}/src/main/cpp  ABSOLUTE)

if (ANDROID_ABI MATCHES "^armeabi-v7a$")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -mfloat-abi=softfp -mfpu=neon")
elseif(ANDROID_ABI MATCHES "^arm64-v8a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O2 -ftree-vectorize")
endif()

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -DSTANDALONE_DEMO_LIB \
                    -std=c++11 -fno-exceptions -fno-rtti -O2 -Wno-narrowing \
                    -fPIE")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} \
                              -Wl,--allow-multiple-definition \
                              -Wl,--whole-archive -fPIE -v")

file(GLOB_RECURSE automobile_sources ${SAMPLE_SRC_DIR}*.*)

add_library(automobile SHARED
        ${SAMPLE_SRC_DIR}/imageutils_jni.cpp
        ${SAMPLE_SRC_DIR}/rgb2yuv.cpp
        ${SAMPLE_SRC_DIR}/rgb2yuv.h
        ${SAMPLE_SRC_DIR}/yuv2rgb.cpp
        ${SAMPLE_SRC_DIR}/yuv2rgb.h
        )

target_include_directories(automobile PRIVATE
        ${SAMPLE_SRC_DIR})

set(OPENCV_SDK "C:/OpenCV4Android/OpenCV-android-sdk")
include_directories(OPENCV_SDK/sdk/native/jni/include)
add_library( opencv SHARED IMPORTED )
set_target_properties( opencv PROPERTIES IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/src/main/jniLibs/${ANDROID_ABI}/libopencv_java3.so)